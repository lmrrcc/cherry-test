package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriceTest {
    @Test
    void appliesDiscount() {
        assertEquals(Boolean.getBoolean("fixture.fail") ? 91 : 90, Price.discounted(100, 10));
    }

    @Test
    void keepsPriceWithoutDiscount() {
        assertEquals(100, Price.discounted(100, 0));
    }
}
