/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.kinesis.connectors.flink.internals.publisher.polling;

import org.apache.flink.annotation.Internal;

import software.amazon.kinesis.connectors.flink.config.ConsumerConfigConstants;
import software.amazon.kinesis.connectors.flink.internals.publisher.RecordPublisher;
import software.amazon.kinesis.connectors.flink.metrics.PollingRecordPublisherMetricsReporter;
import software.amazon.kinesis.connectors.flink.model.SequenceNumber;
import software.amazon.kinesis.connectors.flink.model.StartingPosition;
import software.amazon.kinesis.connectors.flink.model.StreamShardHandle;
import software.amazon.kinesis.connectors.flink.proxy.KinesisProxyInterface;

/**
 * An adaptive record publisher to add a dynamic loop delay and batch read size for {@link PollingRecordPublisher}.
 * Kinesis Streams have quotas on the transactions per second, and throughout. This class attempts to balance
 * quotas and mitigate back off errors.
 */
@Internal
public class AdaptivePollingRecordPublisher extends PollingRecordPublisher {
	// AWS Kinesis has a read limit of 2 Mb/sec
	// https://docs.aws.amazon.com/kinesis/latest/APIReference/API_GetRecords.html
	private static final long KINESIS_SHARD_BYTES_PER_SECOND_LIMIT = 2 * 1024L * 1024L;

	private int lastRecordBatchSize = 0;

	private long lastRecordBatchSizeInBytes = 0;

	private long processingStartTimeNanos = System.nanoTime();

	private int maxNumberOfRecordsPerFetch;

	private final long fetchIntervalMillis;

	private final PollingRecordPublisherMetricsReporter metricsReporter;

	AdaptivePollingRecordPublisher(
			final StartingPosition startingPosition,
			final StreamShardHandle subscribedShard,
			final PollingRecordPublisherMetricsReporter metricsReporter,
			final KinesisProxyInterface kinesisProxy,
			final int maxNumberOfRecordsPerFetch,
			final long fetchIntervalMillis) throws InterruptedException {
		super(startingPosition, subscribedShard, metricsReporter, kinesisProxy, maxNumberOfRecordsPerFetch, fetchIntervalMillis);
		this.maxNumberOfRecordsPerFetch = maxNumberOfRecordsPerFetch;
		this.fetchIntervalMillis = fetchIntervalMillis;
		this.metricsReporter = metricsReporter;
	}

	@Override
	public RecordPublisher.RecordPublisherRunResult run(final RecordPublisher.RecordBatchConsumer consumer) throws InterruptedException {
		final RecordPublisher.RecordPublisherRunResult result = super.run(batch -> {
			SequenceNumber latestSequenceNumber = consumer.accept(batch);
			lastRecordBatchSize = batch.getDeaggregatedRecordSize();
			lastRecordBatchSizeInBytes = batch.getTotalSizeInBytes();
			return latestSequenceNumber;
		}, maxNumberOfRecordsPerFetch);

		long adjustmentEndTimeNanos = adjustRunLoopFrequency(processingStartTimeNanos, System.nanoTime());
		long runLoopTimeNanos = adjustmentEndTimeNanos - processingStartTimeNanos;
		maxNumberOfRecordsPerFetch = adaptRecordsToRead(runLoopTimeNanos, lastRecordBatchSize, lastRecordBatchSizeInBytes, maxNumberOfRecordsPerFetch);
		processingStartTimeNanos = adjustmentEndTimeNanos;
		metricsReporter.setRunLoopTimeNanos(runLoopTimeNanos);

		return result;
	}

	/**
	 * Adjusts loop timing to match target frequency if specified.
	 * @param processingStartTimeNanos The start time of the run loop "work"
	 * @param processingEndTimeNanos The end time of the run loop "work"
	 * @return The System.nanoTime() after the sleep (if any)
	 * @throws InterruptedException
	 */
	private long adjustRunLoopFrequency(long processingStartTimeNanos, long processingEndTimeNanos)
			throws InterruptedException {
		long endTimeNanos = processingEndTimeNanos;
		if (fetchIntervalMillis != 0) {
			long processingTimeNanos = processingEndTimeNanos - processingStartTimeNanos;
			long sleepTimeMillis = fetchIntervalMillis - (processingTimeNanos / 1_000_000);
			if (sleepTimeMillis > 0) {
				Thread.sleep(sleepTimeMillis);
				endTimeNanos = System.nanoTime();
				metricsReporter.setSleepTimeMillis(sleepTimeMillis);
			}
		}
		return endTimeNanos;
	}

	/**
	 * Calculates how many records to read each time through the loop based on a target throughput
	 * and the measured frequenecy of the loop.
	 * @param runLoopTimeNanos The total time of one pass through the loop
	 * @param numRecords The number of records of the last read operation
	 * @param recordBatchSizeBytes The total batch size of the last read operation
	 * @param maxNumberOfRecordsPerFetch The current maxNumberOfRecordsPerFetch
	 */
	private int adaptRecordsToRead(long runLoopTimeNanos, int numRecords, long recordBatchSizeBytes,
			int maxNumberOfRecordsPerFetch) {
		if (numRecords != 0 && runLoopTimeNanos != 0) {
			long averageRecordSizeBytes = recordBatchSizeBytes / numRecords;
			// Adjust number of records to fetch from the shard depending on current average record size
			// to optimize 2 Mb / sec read limits
			double loopFrequencyHz = 1000000000.0d / runLoopTimeNanos;
			double bytesPerRead = KINESIS_SHARD_BYTES_PER_SECOND_LIMIT / loopFrequencyHz;
			maxNumberOfRecordsPerFetch = (int) (bytesPerRead / averageRecordSizeBytes);
			// Ensure the value is greater than 0 and not more than 10000L
			maxNumberOfRecordsPerFetch = Math.max(1, Math.min(maxNumberOfRecordsPerFetch, ConsumerConfigConstants.DEFAULT_SHARD_GETRECORDS_MAX));

			// Set metrics
			metricsReporter.setLoopFrequencyHz(loopFrequencyHz);
			metricsReporter.setBytesPerRead(bytesPerRead);
		}
		return maxNumberOfRecordsPerFetch;
	}
}
