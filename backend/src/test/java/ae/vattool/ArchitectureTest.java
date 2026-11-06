package ae.vattool;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Keeps the modular monolith modular, so services can be split out later (plan: deviation 1). */
@AnalyzeClasses(packages = "ae.vattool", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule platformDependsOnNoBusinessModule = noClasses()
            .that().resideInAPackage("ae.vattool.platform..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ae.vattool.masterdata..", "ae.vattool.ingestion..", "ae.vattool.ledger..",
                    "ae.vattool.rules..", "ae.vattool.returns..", "ae.vattool.adjustments..",
                    "ae.vattool.reconciliation..", "ae.vattool.workflow..", "ae.vattool.filing..",
                    "ae.vattool.evidence..", "ae.vattool.portfolio..", "ae.vattool.demo..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule nothingElseDependsOnDemoData = noClasses()
            .that().resideOutsideOfPackage("ae.vattool.demo..")
            .should().dependOnClassesThat().resideInAPackage("ae.vattool.demo..");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices()
            .matching("ae.vattool.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule onlyTheTenancyModuleTouchesTheTenantSetting = noClasses()
            .that().resideOutsideOfPackage("ae.vattool.platform.tenancy..")
            .should().dependOnClassesThat().haveSimpleName("TenantAwareTransactionManager");
}
