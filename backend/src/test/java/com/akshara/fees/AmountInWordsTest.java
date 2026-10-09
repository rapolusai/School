package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Amounts in words as Indian receipts print them, with lakhs and crores. */
class AmountInWordsTest {

    @Test
    void wholeRupees() {
        assertThat(AmountInWords.rupees(12_500_00)).isEqualTo("Rupees Twelve Thousand Five Hundred Only");
        assertThat(AmountInWords.rupees(1_00)).isEqualTo("Rupees One Only");
        assertThat(AmountInWords.rupees(0)).isEqualTo("Rupees Zero Only");
        assertThat(AmountInWords.rupees(1_000_00)).isEqualTo("Rupees One Thousand Only");
        assertThat(AmountInWords.rupees(1_01_00)).isEqualTo("Rupees One Hundred One Only");
    }

    @Test
    void teensAndTens() {
        assertThat(AmountInWords.words(11)).isEqualTo("Eleven");
        assertThat(AmountInWords.words(19)).isEqualTo("Nineteen");
        assertThat(AmountInWords.words(20)).isEqualTo("Twenty");
        assertThat(AmountInWords.words(99)).isEqualTo("Ninety Nine");
        assertThat(AmountInWords.words(110)).isEqualTo("One Hundred Ten");
    }

    @Test
    void lakhs() {
        assertThat(AmountInWords.rupees(1_84_300_00)).isEqualTo("Rupees One Lakh Eighty Four Thousand Three Hundred Only");
        assertThat(AmountInWords.words(1_00_000)).isEqualTo("One Lakh");
        assertThat(AmountInWords.words(99_99_999)).isEqualTo(
                "Ninety Nine Lakh Ninety Nine Thousand Nine Hundred Ninety Nine");
    }

    @Test
    void crores() {
        assertThat(AmountInWords.words(1_00_00_000)).isEqualTo("One Crore");
        assertThat(AmountInWords.rupees(1_23_45_678_50L)).isEqualTo("Rupees One Crore Twenty Three Lakh Forty Five "
                + "Thousand Six Hundred Seventy Eight and Fifty Paise Only");
        assertThat(AmountInWords.words(150_00_00_000L)).isEqualTo("One Hundred Fifty Crore");
        assertThat(AmountInWords.words(1_00_00_00_00_000L)).isEqualTo("Ten Thousand Crore");
        assertThat(AmountInWords.words(1_000_000_000_000L)).isEqualTo("One Lakh Crore");
    }

    @Test
    void paise() {
        assertThat(AmountInWords.rupees(5)).isEqualTo("Rupees Zero and Five Paise Only");
        assertThat(AmountInWords.rupees(10_75)).isEqualTo("Rupees Ten and Seventy Five Paise Only");
    }

    @Test
    void negativeAmountsAreRefused() {
        assertThatThrownBy(() -> AmountInWords.rupees(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountInWords.words(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
