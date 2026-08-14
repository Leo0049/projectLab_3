package com.bizmcp.suitea;

import com.bizmcp.approval.ApprovalStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval state machine as a table (spec section 8.2).
 *
 * <p>{@code ApprovalFlowTest} walks the common journeys; this pins every
 * legal and illegal edge, including the ones no journey reaches. The rule that
 * matters most is that terminal states are genuinely terminal: an EXECUTED
 * request that could be re-approved would apply the write twice.
 */
class ApprovalStateMachineTest {

    @ParameterizedTest
    @EnumSource(ApprovalStatus.class)
    void terminalStatesAcceptNoTransitions(ApprovalStatus status) {
        if (!status.isTerminal()) {
            return;
        }
        for (ApprovalStatus target : ApprovalStatus.values()) {
            assertThat(status.canTransitionTo(target))
                    .as("%s is terminal but allowed a move to %s", status, target)
                    .isFalse();
        }
    }

    @Test
    void terminalStatesAreExactlyTheExpectedFour() {
        List<ApprovalStatus> terminal = java.util.Arrays.stream(ApprovalStatus.values())
                .filter(ApprovalStatus::isTerminal)
                .toList();

        assertThat(terminal).containsExactlyInAnyOrder(
                ApprovalStatus.EXECUTED,
                ApprovalStatus.REJECTED,
                ApprovalStatus.EXPIRED,
                ApprovalStatus.ABANDONED);
    }

    @Test
    void pendingCanOnlyBeApprovedRejectedOrExpired() {
        assertAllowed(ApprovalStatus.PENDING, Set.of(
                ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, ApprovalStatus.EXPIRED));
    }

    @Test
    void approvedCanOnlySucceedOrFail() {
        assertAllowed(ApprovalStatus.APPROVED, Set.of(
                ApprovalStatus.EXECUTED, ApprovalStatus.EXECUTION_FAILED));
    }

    @Test
    void executionFailedCanBeRetriedOrAbandoned() {
        // Retrying may fail again, so EXECUTION_FAILED -> EXECUTION_FAILED is legal.
        assertAllowed(ApprovalStatus.EXECUTION_FAILED, Set.of(
                ApprovalStatus.EXECUTED,
                ApprovalStatus.EXECUTION_FAILED,
                ApprovalStatus.ABANDONED));
    }

    @Test
    void aFailedExecutionCannotJumpBackToPendingOrApproved() {
        // Re-approving after a failure has to go through a human looking at a
        // fresh preview, not through a state transition.
        assertThat(ApprovalStatus.EXECUTION_FAILED.canTransitionTo(ApprovalStatus.PENDING)).isFalse();
        assertThat(ApprovalStatus.EXECUTION_FAILED.canTransitionTo(ApprovalStatus.APPROVED)).isFalse();
    }

    @Test
    void anExpiredRequestCannotBeRevived() {
        assertThat(ApprovalStatus.EXPIRED.canTransitionTo(ApprovalStatus.APPROVED)).isFalse();
        assertThat(ApprovalStatus.EXPIRED.canTransitionTo(ApprovalStatus.PENDING)).isFalse();
    }

    private void assertAllowed(ApprovalStatus from, Set<ApprovalStatus> allowed) {
        for (ApprovalStatus target : ApprovalStatus.values()) {
            assertThat(from.canTransitionTo(target))
                    .as("%s -> %s", from, target)
                    .isEqualTo(allowed.contains(target));
        }
    }
}
