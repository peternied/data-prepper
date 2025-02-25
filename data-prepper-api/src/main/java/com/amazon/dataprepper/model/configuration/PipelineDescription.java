/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.model.configuration;

/**
 * Model that should be provided to plugins to provide certain details about the pipeline they belong to
 * @since 1.3
 */
public interface PipelineDescription {
    /**
     * Returns the name of the pipeline that a plugin belongs to
     * @since 1.3
     */
    String getPipelineName();

    /**
     * Returns the number of process workers the pipeline is using; plugins can utilize this if synchronization between workers is necessary
     * @since 1.3
     */
    int getNumberOfProcessWorkers();
}
