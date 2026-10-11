package com.akshara.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ISBN check digits and the ISBN-10 to ISBN-13 conversion, against numbers printed on real books. */
class IsbnTest {

    @Test
    void checksIsbn10AndIsbn13CheckDigits() {
        assertThat(Isbn.isValid10("0306406152")).isTrue();
        assertThat(Isbn.isValid10("0306406153")).isFalse();
        assertThat(Isbn.isValid10("080442957X")).isTrue();
        assertThat(Isbn.isValid10("0804429579")).isFalse();
        assertThat(Isbn.isValid10("8173711461")).isTrue();
        assertThat(Isbn.isValid10("030640615")).isFalse();
        assertThat(Isbn.isValid10("X306406152")).isFalse();

        assertThat(Isbn.isValid13("9780306406157")).isTrue();
        assertThat(Isbn.isValid13("9780306406158")).isFalse();
        assertThat(Isbn.isValid13("9788173711466")).isTrue();
        assertThat(Isbn.isValid13("9791090636071")).isTrue();
        // Thirteen digits with a good check digit but not a book number.
        assertThat(Isbn.isValid13("4006381333931")).isFalse();
        assertThat(Isbn.check10("080442957")).isEqualTo('X');
        assertThat(Isbn.check13("978030640615")).isEqualTo('7');
    }

    @Test
    void convertsBetweenIsbn10AndIsbn13() {
        assertThat(Isbn.to13("0306406152")).isEqualTo("9780306406157");
        assertThat(Isbn.to13("080442957X")).isEqualTo("9780804429573");
        assertThat(Isbn.to13("8173711461")).isEqualTo("9788173711466");
        assertThat(Isbn.to10("9780306406157")).contains("0306406152");
        assertThat(Isbn.to10("9780804429573")).contains("080442957X");
        // 979 numbers have no ISBN-10.
        assertThat(Isbn.to10("9791090636071")).isEmpty();
        assertThatThrownBy(() -> Isbn.to13("0306406153")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Isbn.to10("9780306406158")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesIsbnsAsPrintedWithHyphensAndSpaces() {
        assertThat(Isbn.parse("978-81-7371-146-6")).contains(new Isbn.Parsed("9788173711466", "8173711461"));
        assertThat(Isbn.parse("81 7371 146 1")).contains(new Isbn.Parsed("9788173711466", "8173711461"));
        assertThat(Isbn.parse("0-8044-2957-x")).contains(new Isbn.Parsed("9780804429573", "080442957X"));
        assertThat(Isbn.parse("979-10-90636-07-1")).contains(new Isbn.Parsed("9791090636071", null));
        assertThat(Isbn.parse("978-81-7371-146-7")).isEmpty();
        assertThat(Isbn.parse("not an isbn")).isEmpty();
        assertThat(Isbn.parse(null)).isEmpty();
    }

    @Test
    void explainsWhatIsWrong() {
        assertThat(Isbn.problem("978-81-7371-146-6")).isNull();
        assertThat(Isbn.problem("")).isEqualTo("Enter the ISBN.");
        assertThat(Isbn.problem("8173711462")).startsWith("This ISBN-10 does not add up");
        assertThat(Isbn.problem("9788173711467")).startsWith("This ISBN-13 does not add up");
        assertThat(Isbn.problem("4006381333931")).isEqualTo("An ISBN-13 starts with 978 or 979.");
        assertThat(Isbn.problem("12345")).startsWith("An ISBN has 10 or 13 digits");
    }
}
