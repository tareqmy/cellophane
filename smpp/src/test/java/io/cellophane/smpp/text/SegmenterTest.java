package io.cellophane.smpp.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SegmenterTest {

    @Test
    void shortTextsStaySingle() {
        List<Segmenter.Part> gsm = Segmenter.split("a".repeat(160), 0, 1);
        List<Segmenter.Part> ucs = Segmenter.split("আ".repeat(70), 8, 1);

        assertThat(gsm).hasSize(1);
        assertThat(gsm.getFirst().hasUdh()).isFalse();
        assertThat(gsm.getFirst().userData()).hasSize(160);
        assertThat(ucs).hasSize(1);
        assertThat(ucs.getFirst().userData()).hasSize(140);
    }

    @Test
    void longGsm7TextSplitsInto153SeptetPartsWithUdh() {
        String text = "x".repeat(161);

        List<Segmenter.Part> parts = Segmenter.split(text, 0, 0x2A);

        assertThat(parts).hasSize(2);
        assertThat(parts).allMatch(Segmenter.Part::hasUdh);
        Udh first = Udh.parse(parts.get(0).userData());
        assertThat(first.concat()).contains(new Udh.Concat(0x2A, 2, 1));
        assertThat(first.body(parts.get(0).userData())).hasSize(153);
        assertThat(Udh.parse(parts.get(1).userData()).body(parts.get(1).userData())).hasSize(8);
        StringBuilder joined = new StringBuilder();
        for (Segmenter.Part p : parts) {
            Udh udh = Udh.parse(p.userData());
            joined.append(Gsm7.decode(udh.body(p.userData())));
        }
        assertThat(joined.toString()).isEqualTo(text);
    }

    @Test
    void neverSplitsAnEscapePairOrASurrogatePair() {
        String gsm = "a".repeat(152) + "€" + "b".repeat(20); // € is ESC + 0x65 at septets 152-153
        List<Segmenter.Part> parts = Segmenter.split(gsm, 0, 1);
        assertThat(Udh.parse(parts.get(0).userData()).body(parts.get(0).userData())).hasSize(152);
        assertThat(Gsm7.decode(Udh.parse(parts.get(1).userData()).body(parts.get(1).userData()))).startsWith("€b");

        String ucs = "য".repeat(66) + "🙂" + "z".repeat(10); // surrogate pair at chars 66-67
        List<Segmenter.Part> uparts = Segmenter.split(ucs, 8, 1);
        byte[] firstBody = Udh.parse(uparts.get(0).userData()).body(uparts.get(0).userData());
        assertThat(firstBody).hasSize(66 * 2);
        assertThat(SmsText.decode(8, Udh.parse(uparts.get(1).userData()).body(uparts.get(1).userData())))
                .get().asString().startsWith("🙂z");
    }

    @Test
    void ucs2SplitsInto67CharacterParts() {
        List<Segmenter.Part> parts = Segmenter.split("ক".repeat(200), 8, 7);

        assertThat(parts).hasSize(3);
        assertThat(parts).extracting(p -> Udh.parse(p.userData()).concat().orElseThrow().sequence())
                .containsExactly(1, 2, 3);
        assertThat(Udh.parse(parts.get(0).userData()).body(parts.get(0).userData())).hasSize(134);
        assertThat(Udh.parse(parts.get(2).userData()).body(parts.get(2).userData())).hasSize(66 * 2);
    }
}
