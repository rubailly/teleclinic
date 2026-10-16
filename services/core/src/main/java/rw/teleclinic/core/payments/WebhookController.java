package rw.teleclinic.core.payments;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** IremboPay calls this through the public edge. No session: the HMAC signature authenticates the caller. */
@RestController
class WebhookController {

    private final PaymentService payments;

    WebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/webhooks/irembopay")
    Map<String, String> receive(@RequestBody String body,
                                @RequestHeader(value = "X-IremboPay-Signature", required = false) String signature) {
        return Map.of("result", payments.handleWebhook(body, signature).name());
    }
}
