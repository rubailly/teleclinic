package rw.teleclinic.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReleaseFaultFilterTests {

    private static int status(double rate, String uri) throws Exception {
        var filter = new ReleaseFaultFilter(new ReleaseProperties("green", "v1.6", rate));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", uri), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void does_nothing_outside_the_drill() throws Exception {
        for (int i = 0; i < 200; i++) {
            assertThat(status(0, "/api/public/version")).isEqualTo(200);
        }
    }

    @Test
    void fails_api_calls_but_never_health_checks() throws Exception {
        assertThat(status(1, "/api/public/version")).isEqualTo(500);
        assertThat(status(1, "/actuator/health/readiness")).isEqualTo(200);
    }
}
