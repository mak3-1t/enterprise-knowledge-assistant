package com.eka.ingestion.internal.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TeiDocumentRerankerTest {
    @Test
    void postsBoundedTextAndUsesValidatedProviderOrder() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:8081/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"query":"remote policy","texts":["first passage","second passage"],"truncate":true}
                        """))
                .andRespond(
                        withSuccess(
                                """
                                [{"index":1,"score":0.9},{"index":0,"score":0.2}]
                                """,
                                MediaType.APPLICATION_JSON));

        var reranker = new TeiDocumentReranker(builder.build());

        assertThat(
                        reranker.rerank(
                                " remote policy ",
                                List.of(match(1, "first passage"), match(2, "second passage"))))
                .containsExactly(1, 0);
        server.verify();
    }

    @Test
    void rejectsMissingDuplicateAndOutOfRangeRanks() {
        assertThatThrownBy(() -> TeiDocumentReranker.validateAndSort(null, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                TeiDocumentReranker.validateAndSort(
                                        List.of(
                                                new TeiDocumentReranker.Rank(0, 1),
                                                new TeiDocumentReranker.Rank(0, 0)),
                                        2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                TeiDocumentReranker.validateAndSort(
                                        List.of(new TeiDocumentReranker.Rank(1, Double.NaN)), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatesEndpointAndCapsQueryCandidatesAndText() {
        assertThat(TeiDocumentReranker.validateEndpoint("http://localhost:8081"))
                .isEqualTo(URI.create("http://localhost:8081"));
        assertThatThrownBy(() -> TeiDocumentReranker.validateEndpoint("http://u:p@localhost:8081"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TeiDocumentReranker.validateEndpoint("file:///tmp/reranker"))
                .isInstanceOf(IllegalArgumentException.class);
        var reranker = new TeiDocumentReranker(RestClient.builder().build());
        assertThatThrownBy(() -> reranker.rerank(" ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                reranker.rerank(
                                        "q", java.util.Collections.nCopies(11, match(1, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reranker.rerank("q", List.of(match(1, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Match match(int ordinal, String text) {
        return new Match(new UUID(0, ordinal), "doc.md", 1, ordinal, 0, null, 0, 1, text, 0.2);
    }
}
