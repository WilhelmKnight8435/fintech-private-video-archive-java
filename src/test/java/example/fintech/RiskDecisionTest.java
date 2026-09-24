package example.fintech;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RiskDecisionTest {
    @Test void highRiskPaymentNeedsReviewBeforeRoomTokenIsIssued() {
        assertEquals("REVIEW", RiskDecision.forLevel("HIGH"));
        assertEquals("APPROVED", RiskDecision.forLevel("LOW"));
        assertThrows(IllegalArgumentException.class, () -> RiskDecision.forLevel("UNKNOWN"));
    }
}
