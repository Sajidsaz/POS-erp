package com.heysaz.erp.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.heysaz.erp.finance.api.ExpenseCategoryService;
import com.heysaz.erp.platform.error.ApiException;
import com.heysaz.erp.platform.money.Money;
import com.heysaz.erp.support.AbstractIntegrationTest;

/** Acceptance scenarios 9 and 10, against the kernel rather than against checkout. */
class IdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    ExpenseCategoryService service;

    @Test
    void a_repeat_with_the_same_key_and_body_returns_the_original() {
        UUID orgId = createOrganization("IDEM-" + UUID.randomUUID());
        String key = UUID.randomUUID().toString();
        var command = new ExpenseCategoryService.CreateCommand("TRAVEL", "Travel", Money.of(25000));

        asTenant(orgId, () -> {
            ExpenseCategoryService.View first = service.create(command, key);
            ExpenseCategoryService.View second = service.create(command, key);

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(service.listActive()).hasSize(1);
        });
    }

    @Test
    void a_repeat_with_the_same_key_and_a_different_body_is_a_conflict() {
        UUID orgId = createOrganization("IDEM-" + UUID.randomUUID());
        String key = UUID.randomUUID().toString();

        asTenant(orgId, () -> {
            service.create(new ExpenseCategoryService.CreateCommand("A", "First", null), key);

            // FR-API-011: the caller changed its mind mid-retry. That is a client bug and
            // absorbing it silently would hide a real defect.
            assertThatThrownBy(() -> service.create(
                    new ExpenseCategoryService.CreateCommand("B", "Second", null), key))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("different request body");
        });
    }

    @Test
    void the_key_is_scoped_to_the_tenant() {
        UUID orgA = createOrganization("A-" + UUID.randomUUID());
        UUID orgB = createOrganization("B-" + UUID.randomUUID());
        String sharedKey = UUID.randomUUID().toString();
        var command = new ExpenseCategoryService.CreateCommand("SHARED", "Shared", null);

        asTenant(orgA, () -> service.create(command, sharedKey));
        // Two tenants picking the same UUID is vanishingly unlikely but must not couple
        // them, so the scope includes org_id rather than being the key alone.
        asTenant(orgB, () -> assertThat(service.create(command, sharedKey)).isNotNull());
    }

    @Test
    void concurrent_duplicates_produce_exactly_one_row() throws Exception {
        UUID orgId = createOrganization("RACE-" + UUID.randomUUID());
        String key = UUID.randomUUID().toString();
        var command = new ExpenseCategoryService.CreateCommand("RACE", "Race", null);

        int threads = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<UUID>> tasks = java.util.Collections.nCopies(threads, () -> {
                UUID[] result = new UUID[1];
                asTenant(orgId, () -> result[0] = service.create(command, key).id());
                return result[0];
            });
            List<Future<UUID>> futures = pool.invokeAll(tasks);

            // Every caller gets the same id: the losers waited on the unique index and
            // then read the winner's response instead of executing a second time.
            List<UUID> ids = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).distinct().toList();
            assertThat(ids).hasSize(1);
        }

        asTenant(orgId, () -> assertThat(service.listActive()).hasSize(1));
    }
}
