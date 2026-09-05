package io.cellophane.server.api;

import io.cellophane.server.operator.MoInjector;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Inject a mobile-originated message toward a bound receiver, as deliver_sm. */
@RestController
@RequestMapping("/api/v1")
class MoController {

    /** @param account the ESME account to deliver to; optional when only one account has a receiver bound */
    record MoRequest(String from, String to, String text, String account) {
    }

    private final MoInjector injector;

    MoController(MoInjector injector) {
        this.injector = injector;
    }

    /** Sends the message now; 409 if no receiver of the account is bound. */
    @PostMapping("/mo")
    MoInjector.Sent inject(@RequestBody MoRequest request) {
        if (isBlank(request.from()) || isBlank(request.to()) || request.text() == null || request.text().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from, to and text are required");
        }
        try {
            return injector.inject(isBlank(request.account()) ? null : request.account().trim(),
                    request.from().trim(), request.to().trim(), request.text());
        } catch (MoInjector.NoReceiverException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
