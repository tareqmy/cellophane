package io.cellophane.smpp.receipt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;

class DeliveryReceiptTest {

    private static final LocalDateTime SUBMIT = LocalDateTime.of(2026, 9, 4, 12, 0);
    private static final LocalDateTime DONE = LocalDateTime.of(2026, 9, 4, 12, 1);

    @Test
    void formatsTheStandardLayout() {
        DeliveryReceipt r = DeliveryReceipt.of("42", SUBMIT, DONE, DeliveryReceipt.State.DELIVERED,
                "Your OTP is 482913 and it is long");

        assertThat(r.format()).isEqualTo(
                "id:42 sub:001 dlvrd:001 submit date:2609041200 done date:2609041201 stat:DELIVRD err:000"
                + " text:Your OTP is 482913 a");
        assertThat(DeliveryReceipt.of("1", SUBMIT, DONE, DeliveryReceipt.State.UNDELIVERABLE, "x").format())
                .contains("dlvrd:000").contains("stat:UNDELIV").contains("err:001");
    }

    @Test
    void parsesWhatItFormats() {
        DeliveryReceipt r = DeliveryReceipt.of("01M1P8P31CSZQMBS8JG5PMQ04M", SUBMIT, DONE,
                DeliveryReceipt.State.EXPIRED, "hello");

        assertThat(DeliveryReceipt.parse(r.format())).contains(r);
        assertThat(DeliveryReceipt.parse("id:1 sub:001 dlvrd:000 submit date:2609041200 done date:2609041201"
                + " stat:REJECTD err:042")).get().extracting(DeliveryReceipt::state, DeliveryReceipt::text)
                .containsExactly(DeliveryReceipt.State.REJECTED, "");
        assertThat(DeliveryReceipt.parse("not a receipt")).isEmpty();
        assertThat(DeliveryReceipt.parse("id:1 sub:001 dlvrd:000 submit date:2609041200 done date:2609041201"
                + " stat:BOGUS err:000")).isEmpty();
    }

    @Test
    void cloudhopperParsesOurReceipts() throws Exception {
        DeliveryReceipt ours = DeliveryReceipt.of("42", SUBMIT, DONE, DeliveryReceipt.State.DELIVERED, "Your OTP");

        com.cloudhopper.smpp.util.DeliveryReceipt theirs =
                com.cloudhopper.smpp.util.DeliveryReceipt.parseShortMessage(ours.format(), DateTimeZone.UTC);

        assertThat(theirs.getMessageId()).isEqualTo("42");
        assertThat(theirs.getState()).isEqualTo((byte) DeliveryReceipt.State.DELIVERED.code());
        assertThat(theirs.getSubmitCount()).isEqualTo(1);
        assertThat(theirs.getDeliveredCount()).isEqualTo(1);
        assertThat(theirs.getSubmitDate().getMinuteOfHour()).isZero();
        assertThat(theirs.getDoneDate().getMinuteOfHour()).isEqualTo(1);
        assertThat(theirs.getText()).isEqualTo("Your OTP");
    }

    @Test
    void statesMapToTokensAndCodes() {
        assertThat(DeliveryReceipt.State.of("delivrd")).contains(DeliveryReceipt.State.DELIVERED);
        assertThat(DeliveryReceipt.State.of("UNDELIVERABLE")).contains(DeliveryReceipt.State.UNDELIVERABLE);
        assertThat(DeliveryReceipt.State.of("nope")).isEmpty();
        assertThat(DeliveryReceipt.State.ofCode(8)).contains(DeliveryReceipt.State.REJECTED);
        assertThat(DeliveryReceipt.State.DELIVERED.isFinal()).isTrue();
        assertThat(DeliveryReceipt.State.ENROUTE.isFinal()).isFalse();
    }
}
