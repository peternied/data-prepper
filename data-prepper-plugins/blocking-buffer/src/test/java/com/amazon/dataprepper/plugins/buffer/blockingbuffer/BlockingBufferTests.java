/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.plugins.buffer.blockingbuffer;

import com.amazon.dataprepper.model.buffer.SizeOverflowException;
import com.amazon.dataprepper.model.configuration.PluginSetting;
import com.amazon.dataprepper.model.record.Record;
import com.amazon.dataprepper.model.CheckpointState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BlockingBufferTests {
    private static final String ATTRIBUTE_BATCH_SIZE = "batch_size";
    private static final String ATTRIBUTE_BUFFER_SIZE = "buffer_size";
    private static final String TEST_PIPELINE_NAME = "test-pipeline";
    private static final int TEST_BATCH_SIZE = 3;
    private static final int TEST_BUFFER_SIZE = 13;
    private static final int TEST_WRITE_TIMEOUT = 1_00;
    private static final int TEST_BATCH_READ_TIMEOUT = 5_000;

    @Test
    public void testCreationUsingPluginSetting() {
        final PluginSetting completePluginSetting = completePluginSettingForBlockingBuffer();
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(completePluginSetting);
        assertThat(blockingBuffer, notNullValue());
    }

    @Test
    public void testCreationUsingNullPluginSetting() {
        try {
            new BlockingBuffer<Record<String>>((PluginSetting) null);
        } catch (NullPointerException ex) {
            assertThat(ex.getMessage(), is(equalTo("PluginSetting cannot be null")));
        }
    }

    @Test
    public void testCreationUsingDefaultPluginSettings() {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(
                BlockingBuffer.getDefaultPluginSettings());
        assertThat(blockingBuffer, notNullValue());
    }

    @Test
    public void testCreationUsingValues() {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(TEST_BUFFER_SIZE, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
    }

    @Test
    public void testInsertNull() throws TimeoutException {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(TEST_BUFFER_SIZE, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        assertThrows(NullPointerException.class, () -> blockingBuffer.write(null, TEST_WRITE_TIMEOUT));
    }

    @Test
    public void testWriteAllSizeOverflow() throws Exception {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(TEST_BUFFER_SIZE, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        final Collection<Record<String>> testRecords = generateBatchRecords(TEST_BUFFER_SIZE + 1);
        assertThrows(SizeOverflowException.class, () -> blockingBuffer.writeAll(testRecords, TEST_WRITE_TIMEOUT));
    }

    @Test
    public void testNoEmptySpaceWriteOnly() throws TimeoutException {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(1, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        blockingBuffer.write(new Record<>("FILL_THE_BUFFER"), TEST_WRITE_TIMEOUT);
        assertThrows(TimeoutException.class, () -> blockingBuffer.write(new Record<>("TIMEOUT"), TEST_WRITE_TIMEOUT));
    }

    @Test
    public void testNoAvailSpaceWriteAllOnly() throws Exception {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(2, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        final Collection<Record<String>> testRecords = generateBatchRecords(2);
        blockingBuffer.write(new Record<>("FILL_THE_BUFFER"), TEST_WRITE_TIMEOUT);
        assertThrows(TimeoutException.class, () -> blockingBuffer.writeAll(testRecords, TEST_WRITE_TIMEOUT));
    }

    @Test
    public void testNoEmptySpaceAfterUncheckedRead() throws TimeoutException {
        // Given
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(1, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        blockingBuffer.write(new Record<>("FILL_THE_BUFFER"), TEST_WRITE_TIMEOUT);

        // When
        blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);

        // Then
        final Record<String> timeoutRecord = new Record<>("TIMEOUT");
        assertThrows(TimeoutException.class, () -> blockingBuffer.write(timeoutRecord, TEST_WRITE_TIMEOUT));
        assertThrows(
                TimeoutException.class, () -> blockingBuffer.writeAll(Collections.singletonList(timeoutRecord), TEST_WRITE_TIMEOUT));
    }

    @Test
    public void testWriteIntoEmptySpaceAfterCheckedRead() throws TimeoutException {
        // Given
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(1, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        blockingBuffer.write(new Record<>("FILL_THE_BUFFER"), TEST_WRITE_TIMEOUT);

        // When
        final Map.Entry<Collection<Record<String>>, CheckpointState> readResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        blockingBuffer.checkpoint(readResult.getValue());

        // Then
        blockingBuffer.write(new Record<>("REFILL_THE_BUFFER"), TEST_WRITE_TIMEOUT);
        final Map.Entry<Collection<Record<String>>, CheckpointState> readCheckResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        assertEquals(1, readCheckResult.getKey().size());
    }

    @Test
    public void testWriteAllIntoEmptySpaceAfterCheckedRead() throws Exception {
        // Given
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(2, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        final Collection<Record<String>> testRecords = generateBatchRecords(2);
        blockingBuffer.writeAll(testRecords, TEST_WRITE_TIMEOUT);

        // When
        final Map.Entry<Collection<Record<String>>, CheckpointState> readResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        blockingBuffer.checkpoint(readResult.getValue());

        // Then
        blockingBuffer.writeAll(testRecords, TEST_WRITE_TIMEOUT);
        final Map.Entry<Collection<Record<String>>, CheckpointState> readCheckResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        assertEquals(2, readCheckResult.getKey().size());
    }

    @Test
    public void testReadEmptyBuffer() {
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(TEST_BUFFER_SIZE, TEST_BATCH_SIZE,
                TEST_PIPELINE_NAME);
        assertThat(blockingBuffer, notNullValue());
        final Map.Entry<Collection<Record<String>>, CheckpointState> readResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        assertThat(readResult.getKey().size(), is(0));
    }

    @Test
    public void testBatchRead() throws Exception {
        final PluginSetting completePluginSetting = completePluginSettingForBlockingBuffer();
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(completePluginSetting);
        assertThat(blockingBuffer, notNullValue());
        final int testSize = 5;
        for (int i = 0; i < testSize; i++) {
            Record<String> record = new Record<>("TEST" + i);
            blockingBuffer.write(record, TEST_WRITE_TIMEOUT);
        }
        final Map.Entry<Collection<Record<String>>, CheckpointState> partialReadResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        final Collection<Record<String>> partialRecords = partialReadResult.getKey();
        final CheckpointState partialCheckpointState = partialReadResult.getValue();
        final int expectedBatchSize = (Integer) completePluginSetting.getAttributeFromSettings(ATTRIBUTE_BATCH_SIZE);
        assertThat(partialRecords.size(), is(expectedBatchSize));
        assertEquals(expectedBatchSize, partialCheckpointState.getNumRecordsToBeChecked());
        int i = 0;
        for (Record<String> record : partialRecords) {
            assertThat(record.getData(), equalTo("TEST" + i));
            i++;
        }
        final Map.Entry<Collection<Record<String>>, CheckpointState> finalReadResult = blockingBuffer.read(TEST_BATCH_READ_TIMEOUT);
        final Collection<Record<String>> finalBatch = finalReadResult.getKey();
        final CheckpointState finalCheckpointState = finalReadResult.getValue();
        assertThat(finalBatch.size(), is(testSize - expectedBatchSize));
        assertEquals(testSize - expectedBatchSize, finalCheckpointState.getNumRecordsToBeChecked());
        for (Record<String> record : finalBatch) {
            assertThat(record.getData(), equalTo("TEST" + i));
            i++;
        }
    }

    @Test
    public void testBufferIsEmpty() {
        final PluginSetting completePluginSetting = completePluginSettingForBlockingBuffer();
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(completePluginSetting);

        assertTrue(blockingBuffer.isEmpty());
    }

    @Test
    public void testBufferIsNotEmpty() throws Exception {
        final PluginSetting completePluginSetting = completePluginSettingForBlockingBuffer();
        final BlockingBuffer<Record<String>> blockingBuffer = new BlockingBuffer<>(completePluginSetting);

        Record<String> record = new Record<>("TEST");
        blockingBuffer.write(record, TEST_WRITE_TIMEOUT);

        assertFalse(blockingBuffer.isEmpty());
    }

    private PluginSetting completePluginSettingForBlockingBuffer() {
        final String pluginName = "bounded_blocking";
        final Map<String, Object> settings = new HashMap<>();
        settings.put(ATTRIBUTE_BUFFER_SIZE, TEST_BUFFER_SIZE);
        settings.put(ATTRIBUTE_BATCH_SIZE, TEST_BATCH_SIZE);
        final PluginSetting testSettings = new PluginSetting(pluginName, settings);
        testSettings.setPipelineName(TEST_PIPELINE_NAME);
        return testSettings;
    }

    private Collection<Record<String>> generateBatchRecords(final int numRecords) {
        final Collection<Record<String>> results = new ArrayList<>();
        for (int i = 0; i < numRecords; i++) {
            results.add(new Record<>(UUID.randomUUID().toString()));
        }
        return results;
    }
}
