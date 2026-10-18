package rw.teleclinic.core.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rollback drill only: makes a share of API calls fail with 500, the way a bad build would, while health checks
 * stay green (a bad build usually passes them). The release gate has to catch it from real traffic.
 */
@Component
class ReleaseFaultFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ReleaseFaultFilter.class);
    private final double rate;

    ReleaseFaultFilter(ReleaseProperties release) {
        this.rate = release.faultRate();
        if (rate > 0) {
            log.warn("Release drill: {}% of API calls will fail on purpose ({} {})", Math.round(rate * 100),
                    release.version(), release.color());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rate <= 0 || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            response.setStatus(500);
            response.setContentType("application/json");
            response.getWriter().write("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                    + "\"code\":\"exception\",\"diagnostics\":\"injected fault (release drill)\"}]}");
            return;
        }
        chain.doFilter(request, response);
    }
}
