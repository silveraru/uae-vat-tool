package ae.vattool.returns;

import java.util.UUID;

/**
 * Published inside the recompute transaction when a period's ledger data has changed. Workflow
 * listens, so sign-offs given on the old data stop counting. This keeps the return engine free of
 * any dependency on workflow.
 */
public record PeriodDataChanged(UUID periodId, String newFingerprint) {}
