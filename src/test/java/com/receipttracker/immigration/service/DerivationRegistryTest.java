package com.receipttracker.immigration.service;

import com.receipttracker.immigration.model.CaseType;
import com.receipttracker.immigration.model.ImmigrationCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DerivationRegistryTest {

    private final DerivationRegistry registry = new DerivationRegistry();

    private DataResolver.ResolutionContext ctxFor(CaseType type) {
        ImmigrationCase c = new ImmigrationCase();
        c.setId(1L);
        c.setCaseType(type);
        return DataResolver.ResolutionContext.of(null, null, null, c, null);
    }

    @Test
    void knowsAllRegisteredFunctions() {
        assertThat(registry.knows("classificationSymbol")).isTrue();
        assertThat(registry.knows("basisForClassification")).isTrue();
        assertThat(registry.knows("requestedAction")).isTrue();
        assertThat(registry.knows("totalWorkers")).isTrue();
        assertThat(registry.knows("nonexistent")).isFalse();
        assertThat(registry.knows(null)).isFalse();
    }

    @Test
    void classificationSymbolForH1bFamily() {
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H1B_INITIAL))).isEqualTo("H-1B");
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H1B_EXTENSION))).isEqualTo("H-1B");
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H1B_TRANSFER))).isEqualTo("H-1B");
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H1B_AMENDMENT))).isEqualTo("H-1B");
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H4))).isEqualTo("H-4");
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.H4_EAD))).isEqualTo("H-4");
    }

    @Test
    void classificationSymbolNullOutsideMapping() {
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.PERM))).isNull();
        assertThat(registry.derive("classificationSymbol", ctxFor(CaseType.NATURALIZATION))).isNull();
    }

    @Test
    void basisForClassificationByCaseType() {
        assertThat(registry.derive("basisForClassification", ctxFor(CaseType.H1B_INITIAL))).isEqualTo("new employment");
        assertThat(registry.derive("basisForClassification", ctxFor(CaseType.H1B_EXTENSION))).isEqualTo("continuation");
        assertThat(registry.derive("basisForClassification", ctxFor(CaseType.H1B_TRANSFER))).isEqualTo("change of employer");
        assertThat(registry.derive("basisForClassification", ctxFor(CaseType.H1B_AMENDMENT))).isEqualTo("amended petition");
        assertThat(registry.derive("basisForClassification", ctxFor(CaseType.I485))).isNull();
    }

    @Test
    void requestedActionByCaseType() {
        assertThat(registry.derive("requestedAction", ctxFor(CaseType.H1B_INITIAL))).isEqualTo("notify office");
        assertThat(registry.derive("requestedAction", ctxFor(CaseType.H1B_EXTENSION))).isEqualTo("extend");
        assertThat(registry.derive("requestedAction", ctxFor(CaseType.H1B_TRANSFER))).isEqualTo("change status");
        assertThat(registry.derive("requestedAction", ctxFor(CaseType.H1B_AMENDMENT))).isNull();
    }

    @Test
    void totalWorkersIsAlwaysOne() {
        assertThat(registry.derive("totalWorkers", ctxFor(CaseType.H1B_INITIAL))).isEqualTo("1");
        assertThat(registry.derive("totalWorkers", ctxFor(CaseType.PERM))).isEqualTo("1");
    }

    @Test
    void unknownDerivationReturnsNull() {
        assertThat(registry.derive("nonexistent", ctxFor(CaseType.H1B_INITIAL))).isNull();
    }

    @Test
    void nullCaseIsHandledGracefully() {
        DataResolver.ResolutionContext ctx =
                DataResolver.ResolutionContext.of(null, null, null, null, null);
        assertThat(registry.derive("classificationSymbol", ctx)).isNull();
        assertThat(registry.derive("basisForClassification", ctx)).isNull();
        assertThat(registry.derive("requestedAction", ctx)).isNull();
        assertThat(registry.derive("totalWorkers", ctx)).isEqualTo("1");
    }
}
