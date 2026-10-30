package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AddressBudgetTest {

    @Test
    void allowsTheBudgetPerAddressAndRefusesTheRest() {
        AddressBudget budget = new AddressBudget(2, Duration.ofMinutes(10));
        HttpServletRequest first = from("203.0.113.5");

        assertThat(budget.allow(first)).isTrue();
        assertThat(budget.allow(first)).isTrue();
        assertThat(budget.allow(first)).isFalse();
    }

    @Test
    void anotherAddressHasItsOwnBudget() {
        AddressBudget budget = new AddressBudget(1, Duration.ofMinutes(10));

        assertThat(budget.allow(from("203.0.113.5"))).isTrue();
        assertThat(budget.allow(from("203.0.113.5"))).isFalse();
        assertThat(budget.allow(from("203.0.113.6"))).isTrue();
    }

    private static HttpServletRequest from(String address) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(address);
        return request;
    }
}
