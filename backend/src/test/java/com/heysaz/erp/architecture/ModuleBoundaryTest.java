package com.heysaz.erp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Section 3.1 says modules communicate through application services rather than reaching
 * into each other's internals. This is what makes that a property of the build rather
 * than a paragraph everyone agrees with and then forgets.
 *
 * <p>Adding a module means adding its name to {@link #MODULES}. That is the intended
 * friction: a new module without a boundary rule is a new module without a boundary.
 */
class ModuleBoundaryTest {

    private static final List<String> MODULES =
            List.of("finance", "identity", "organization", "catalog", "inventory", "pos",
                    "purchasing", "customer");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.heysaz.erp");

    @Test
    void module_internals_are_reachable_only_from_inside_the_module() {
        for (String module : MODULES) {
            noClasses()
                    .that().resideOutsideOfPackage("..%s..".formatted(module))
                    .should().accessClassesThat()
                    .resideInAnyPackage("..%s.internal..".formatted(module),
                                        "..%s.domain..".formatted(module))
                    .as("no class outside '%s' may reach into %s.internal or %s.domain"
                            .formatted(module, module, module))
                    .check(classes);
        }
    }

    @Test
    void the_platform_kernel_does_not_depend_on_any_business_module() {
        // Dependencies point inward. A kernel that knew about finance would stop being a
        // kernel and start being the thing every module has to be deployed with.
        noClasses()
                .that().resideInAPackage("..platform..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(MODULES.stream()
                        .map("..%s.."::formatted)
                        .toArray(String[]::new))
                .check(classes);
    }

    @Test
    void controllers_do_not_reach_the_database_directly() {
        // Web talks to api, api is implemented by internal, internal owns SQL. A
        // controller holding a JdbcClient is how transaction boundaries go missing.
        noClasses()
                .that().resideInAPackage("..web..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(classes);
    }

    /**
     * DB-011 and decision D1: the elevated pool bypasses row-level security, so its use is
     * confined to authentication (which runs before a tenant exists) and the outbox worker
     * (which spans all of them). A source scan rather than a bytecode rule, because what
     * matters is the {@code @Qualifier} string, which is erased by the time ArchUnit sees it.
     */
    @Test
    void the_rls_escape_hatch_is_confined_to_the_places_that_justify_it() throws IOException {
        List<String> allowed = List.of(
                "platform/config/DataAccessConfig.java",
                "platform/outbox/OutboxWorker.java",
                "platform/security/TokenService.java",
                // Login reads platform.app_user before any tenant is known. Everything it
                // then needs from tenant-scoped tables — permissions, shop scope — goes
                // through TenantContext.runAsOrg on the RLS-bound pool instead.
                "identity/internal/AuthenticationServiceImpl.java");

        Path sourceRoot = Path.of("src/main/java/com/heysaz/erp");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<String> offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String relative = sourceRoot.relativize(p).toString().replace('\\', '/');
                        return allowed.stream().noneMatch(relative::equals);
                    })
                    .filter(ModuleBoundaryTest::mentionsElevatedDataSource)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("only authentication and the outbox worker may use the elevated, "
                        + "RLS-bypassing connection")
                    .isEmpty();
        }
    }

    private static boolean mentionsElevatedDataSource(Path path) {
        try {
            String source = Files.readString(path);
            return source.contains("elevatedJdbcClient")
                    || source.contains("elevatedDataSource")
                    || source.contains("elevatedTransactionTemplate");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
