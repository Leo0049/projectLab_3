package com.bizmcp.suitea;

import com.bizmcp.masking.MaskStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The masking rules themselves (spec section 7.2).
 *
 * <p>These are pure string functions, and they are the last thing standing
 * between a customer's phone number and a chat window — so the edge cases get
 * asserted directly rather than being inferred from one happy-path record in
 * an integration test. Short names and malformed input are where naive
 * implementations leak or crash.
 */
class MaskStrategyTest {

    @ParameterizedTest(name = "NAME: {0} -> {1}")
    @CsvSource({
            "王小明,   王○明",
            "陳美麗,   陳○麗",
            "李大文,   李○文",
            "王明,     王○",
            "歐陽小明, 歐○○明",
            "王,       王"
    })
    void nameKeepsFirstAndLastCharacter(String raw, String expected) {
        assertThat(MaskStrategy.NAME.apply(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "PHONE: {0} -> {1}")
    @CsvSource({
            "0912345678,   0912***678",
            "0912-345-678, 0912***678",
            "02-27001234,  0227***234"
    })
    void phoneKeepsPrefixAndLastThreeDigits(String raw, String expected) {
        assertThat(MaskStrategy.PHONE.apply(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "PHONE too short: {0}")
    @ValueSource(strings = {"12345", "abc", "0912"})
    void phoneWithTooFewDigitsIsRemovedEntirely(String raw) {
        // Better to lose the value than to emit something a short number could
        // be reconstructed from.
        assertThat(MaskStrategy.PHONE.apply(raw)).isEqualTo("***");
    }

    @Test
    void addressKeepsOnlyTheAdministrativeDistrict() {
        assertThat(MaskStrategy.ADDRESS.apply("台北市信義區松高路11號5樓"))
                .isEqualTo("台北市信義區***");
        assertThat(MaskStrategy.ADDRESS.apply("台北市"))
                .isEqualTo("台北市***");
    }

    @ParameterizedTest(name = "EMAIL: {0} -> {1}")
    @CsvSource({
            "ming@example.com, m***@example.com",
            "a@b.co,           a***@b.co"
    })
    void emailKeepsTheDomainAndOneCharacter(String raw, String expected) {
        assertThat(MaskStrategy.EMAIL.apply(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "malformed email: {0}")
    @ValueSource(strings = {"not-an-email", "@example.com"})
    void malformedEmailIsRemovedEntirely(String raw) {
        assertThat(MaskStrategy.EMAIL.apply(raw)).isEqualTo("***");
    }

    @Test
    void fullStrategyRemovesEverything() {
        assertThat(MaskStrategy.FULL.apply("anything at all")).isEqualTo("***");
    }

    @ParameterizedTest(name = "null/empty passes through for {0}")
    @ValueSource(strings = {"NAME", "PHONE", "ADDRESS", "EMAIL", "FULL"})
    void nullAndEmptyValuesArePassedThrough(String strategyName) {
        MaskStrategy strategy = MaskStrategy.valueOf(strategyName);
        assertThat(strategy.applyNullSafe(null)).isNull();
        assertThat(strategy.applyNullSafe("")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullSafeVariantNeverThrows(String raw) {
        for (MaskStrategy strategy : MaskStrategy.values()) {
            assertThat(strategy.applyNullSafe(raw)).isEqualTo(raw);
        }
    }

    @Test
    void maskedOutputNeverContainsTheOriginalSensitivePortion() {
        assertThat(MaskStrategy.PHONE.apply("0912345678")).doesNotContain("34567");
        assertThat(MaskStrategy.NAME.apply("王小明")).doesNotContain("小");
        assertThat(MaskStrategy.ADDRESS.apply("台北市信義區松高路11號5樓")).doesNotContain("松高路");
        assertThat(MaskStrategy.EMAIL.apply("ming@example.com")).doesNotContain("ming");
    }
}
