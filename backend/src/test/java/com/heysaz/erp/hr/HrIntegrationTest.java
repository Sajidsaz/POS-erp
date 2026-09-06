package com.heysaz.erp.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.hr.api.HrService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.tenant.Principal;
import com.heysaz.erp.platform.tenant.TenantContext;
import com.heysaz.erp.support.AbstractIntegrationTest;

/**
 * Milestone / R1 — HR: employees, the FR-HR-006 user link, attendance check-in/out and
 * manual-entry approval, and leave request/approval with balance tracking (FR-HR-001..003).
 */
class HrIntegrationTest extends AbstractIntegrationTest {

    @Autowired HrService hrService;

    private void asHr(UUID orgId, UUID userId, Runnable body) {
        TenantContext.set(new Principal(Principal.PrincipalType.USER_SESSION, userId, orgId,
                "hr", Set.of(), Set.of("employees.read", "employees.write"), "T1",
                java.time.Instant.now()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private HrService.EmployeeView newEmployee(String code, UUID userId) {
        return hrService.createEmployee(new HrService.CreateEmployeeCommand(
                code, "Jane " + code, "Cashier", "FULL_TIME", "0771", code + "@x.test",
                userId, LocalDate.of(2024, 1, 1)));
    }

    @Test
    void an_employee_links_to_at_most_one_user_account() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("HR-" + suffix);
        UUID actor = createUser(orgId, "hr-" + suffix + "@heysaz.test");
        UUID linkedUser = createUser(orgId, "cashier-" + suffix + "@heysaz.test");

        asHr(orgId, actor, () -> {
            var e1 = newEmployee("E1-" + suffix, linkedUser);
            assertThat(e1.userId()).isEqualTo(linkedUser);
            assertThat(hrService.getEmployeeByUser(linkedUser)).isPresent()
                    .get().satisfies(e -> assertThat(e.id()).isEqualTo(e1.id()));

            // FR-HR-006: the same login cannot back a second employee.
            assertThatThrownBy(() -> newEmployee("E2-" + suffix, linkedUser))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already linked");
        });
    }

    @Test
    void attendance_records_live_check_in_out_and_holds_manual_entries_for_approval() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("HRA-" + suffix);
        UUID actor = createUser(orgId, "hr-" + suffix + "@heysaz.test");

        asHr(orgId, actor, () -> {
            var emp = newEmployee("E-" + suffix, null);

            var in = hrService.checkIn(emp.id());
            assertThat(in.status()).isEqualTo("RECORDED");
            assertThat(in.checkInAt()).isNotNull();
            assertThat(in.checkOutAt()).isNull();

            assertThatThrownBy(() -> hrService.checkIn(emp.id()))
                    .isInstanceOf(ApiException.class).hasMessageContaining("already checked in");

            var out = hrService.checkOut(emp.id());
            assertThat(out.checkOutAt()).isNotNull();

            var manual = hrService.recordManualAttendance(new HrService.ManualAttendanceCommand(
                    emp.id(), LocalDate.now().minusDays(2), null, null, "forgot to clock in"));
            assertThat(manual.status()).isEqualTo("PENDING");

            var approved = hrService.approveAttendance(manual.id(), true);
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(approved.approvedBy()).isEqualTo(actor);
        });
    }

    @Test
    void leave_tracks_balance_and_refuses_a_request_beyond_it() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createProvisionedOrganization("HRL-" + suffix);
        UUID actor = createUser(orgId, "hr-" + suffix + "@heysaz.test");

        asHr(orgId, actor, () -> {
            var emp = newEmployee("E-" + suffix, null);
            hrService.setLeaveEntitlement(emp.id(), "ANNUAL", new BigDecimal("14"));

            assertThat(hrService.leaveBalance(emp.id())).singleElement().satisfies(b -> {
                assertThat(b.entitledDays()).isEqualByComparingTo("14");
                assertThat(b.usedDays()).isEqualByComparingTo("0");
                assertThat(b.remainingDays()).isEqualByComparingTo("14");
            });

            LocalDate start = LocalDate.of(2026, 6, 1);
            var request = hrService.requestLeave(new HrService.RequestLeaveCommand(
                    emp.id(), "ANNUAL", start, start.plusDays(2), "holiday"));
            assertThat(request.days()).isEqualByComparingTo("3");
            assertThat(request.status()).isEqualTo("REQUESTED");

            var decided = hrService.decideLeave(request.id(), true, "ok");
            assertThat(decided.status()).isEqualTo("APPROVED");

            assertThat(hrService.leaveBalance(emp.id())).singleElement().satisfies(b -> {
                assertThat(b.usedDays()).isEqualByComparingTo("3");
                assertThat(b.remainingDays()).isEqualByComparingTo("11");
            });

            // 12 days requested against 11 remaining must be refused on approval.
            var tooMuch = hrService.requestLeave(new HrService.RequestLeaveCommand(
                    emp.id(), "ANNUAL", start.plusMonths(1), start.plusMonths(1).plusDays(11), "long"));
            assertThatThrownBy(() -> hrService.decideLeave(tooMuch.id(), true, null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Insufficient");
        });
    }
}
