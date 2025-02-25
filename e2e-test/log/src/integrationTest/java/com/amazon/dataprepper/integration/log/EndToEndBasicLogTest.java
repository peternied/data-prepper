/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.integration.log;

import com.amazon.dataprepper.plugins.sink.opensearch.ConnectionConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import io.netty.util.AsciiString;
import org.junit.Assert;
import org.junit.Test;
import org.opensearch.action.admin.indices.refresh.RefreshRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.search.SearchHits;
import org.opensearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

public class EndToEndBasicLogTest {
    private static final int HTTP_SOURCE_PORT = 2021;
    private static final String TEST_INDEX_NAME = "test-grok-index";

    private final ApacheLogFaker apacheLogFaker = new ApacheLogFaker();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testPipelineEndToEnd() throws JsonProcessingException {
        // Send data to http source
        sendHttpRequestToSource(HTTP_SOURCE_PORT, generateRandomApacheLogHttpData(2));
        sendHttpRequestToSource(HTTP_SOURCE_PORT, generateRandomApacheLogHttpData(3));

        // Verify data in OpenSearch backend
        final RestHighLevelClient restHighLevelClient = prepareOpenSearchRestHighLevelClient();
        final List<Map<String, Object>> retrievedDocs = new ArrayList<>();
        // Wait for data to flow through pipeline and be indexed by ES
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(
                () -> {
                    refreshIndices(restHighLevelClient);
                    final SearchRequest searchRequest = new SearchRequest(TEST_INDEX_NAME);
                    searchRequest.source(
                            SearchSourceBuilder.searchSource().size(100)
                    );
                    final SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
                    final List<Map<String, Object>> foundSources = getSourcesFromSearchHits(searchResponse.getHits());
                    Assert.assertEquals(5, foundSources.size());
                    retrievedDocs.addAll(foundSources);
                }
        );
        // Verify original and grokked keys from retrieved docs
        final List<String> expectedDocKeys = Arrays.asList(
                "date", "log", "clientip", "ident", "auth", "timestamp", "verb", "request", "httpversion", "response", "bytes");
        retrievedDocs.forEach(expectedDoc -> {
            for (String key: expectedDocKeys) {
                assertThat(expectedDoc, hasKey(key));
            }
        });
    }

    private RestHighLevelClient prepareOpenSearchRestHighLevelClient() {
        final ConnectionConfiguration.Builder builder = new ConnectionConfiguration.Builder(
                Collections.singletonList("https://127.0.0.1:9200"));
        builder.withUsername("admin");
        builder.withPassword("admin");
        return builder.build().createClient();
    }

    private void sendHttpRequestToSource(final int port, final HttpData httpData) {
        WebClient.of().execute(RequestHeaders.builder()
                        .scheme(SessionProtocol.HTTP)
                        .authority(String.format("127.0.0.1:%d", port))
                        .method(HttpMethod.POST)
                        .path("/log/ingest")
                        .contentType(MediaType.JSON_UTF_8)
                        .build(),
                httpData)
                .aggregate()
                .whenComplete((i, ex) -> {
                    assertThat(i.status(), is(HttpStatus.OK));
                    final List<String> headerKeys = i.headers()
                            .stream()
                            .map(Map.Entry::getKey)
                            .map(AsciiString::toString)
                            .collect(Collectors.toList());
                    assertThat("Response Header Keys", headerKeys, not(contains("server")));
                }).join();
    }

    private List<Map<String, Object>> getSourcesFromSearchHits(final SearchHits searchHits) {
        final List<Map<String, Object>> sources = new ArrayList<>();
        searchHits.forEach(hit -> {
            Map<String, Object> source = hit.getSourceAsMap();
            sources.add(source);
        });
        return sources;
    }

    private void refreshIndices(final RestHighLevelClient restHighLevelClient) throws IOException {
        final RefreshRequest requestAll = new RefreshRequest();
        restHighLevelClient.indices().refresh(requestAll, RequestOptions.DEFAULT);
    }

    private HttpData generateRandomApacheLogHttpData(final int numLogs) throws JsonProcessingException {
        final List<Map<String, Object>> jsonArray = new ArrayList<>();
        for (int i = 0; i < numLogs; i++) {
            final Map<String, Object> logObj = new HashMap<String, Object>() {{
                put("date", System.currentTimeMillis());
                put("log", apacheLogFaker.generateRandomApacheLog());
            }};
            jsonArray.add(logObj);
        }
        final String jsonData = objectMapper.writeValueAsString(jsonArray);
        return HttpData.ofUtf8(jsonData);
    }
}
