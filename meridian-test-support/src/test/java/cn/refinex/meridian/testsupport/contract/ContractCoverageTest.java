package cn.refinex.meridian.testsupport.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContractCoverageTest {

    private static final String SPEC = "contract/coverage-spec.yaml";

    @Test
    void pathsMatchTheMostSpecificTemplate() {
        assertThat(ContractCoverage.match(SPEC, "post", "/v1/sessions/ses_1:archive"))
                .map(ContractCoverage.Operation::name)
                .hasValue("POST /v1/sessions/{session_id}:archive");
        assertThat(ContractCoverage.match(SPEC, "GET", "/v1/sessions/ses_1"))
                .map(ContractCoverage.Operation::name)
                .hasValue("GET /v1/sessions/{session_id}");
        assertThat(ContractCoverage.match(SPEC, "GET", "/v1/sessions/ses_1/workspace/files/outputs%2Fa.docx"))
                .map(ContractCoverage.Operation::name)
                .hasValue("GET /v1/sessions/{session_id}/workspace/files/{path}");
        assertThat(ContractCoverage.match(SPEC, "GET", "/v1/sessions/ses_1/threads"))
                .isEmpty();
        assertThat(ContractCoverage.match(SPEC, "POST", "/v1/sessions/ses_1")).isEmpty();
    }

    @Test
    void uncoveredOperationsAreReportedPerTag() {
        List<String> sessions = List.of("Sessions");
        assertThat(ContractCoverage.operations(SPEC, sessions)).hasSize(3);

        ContractCoverage.record(SPEC, "GET", "/v1/sessions/ses_1?view=full");
        ContractCoverage.record(SPEC, "POST", "/v1/sessions/ses_1:archive");

        assertThat(ContractCoverage.uncovered(SPEC, sessions)).containsExactly("DELETE /v1/sessions/{session_id}");
        assertThat(ContractCoverage.tags(SPEC)).containsExactly("Agents", "Sessions", "Workspace");
    }
}
