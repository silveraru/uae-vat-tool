export interface Grant { entityId: string | null; role: string }
export interface Me { id: string; displayName: string; tenantName: string; email: string; side: string; grants: Grant[] }

export interface Entity {
  id: string; legalName: string; legalNameAr: string | null; trn: string | null; entityKind: string; groupRepId: string | null;
  filingFrequency: string | null; stagger: string | null; firstPeriodStart: string | null; einvoiceGoLive: string | null;
  defaultRecoveryPct: string; partnerThresholdAed: string | null; requireReviewer: boolean;
}
export interface Establishment { id: string; code: string; name: string; emirate: string; designatedZone: boolean; zoneName: string | null }
export interface Period {
  id: string; entityId: string; periodStart: string; periodEnd: string; dueDate: string; state: string;
  lockedVersionId: string | null; lockedAt: string | null;
}
export interface MappingVersion { id: string; versionNo: number; effectiveFrom: string; note: string | null; createdBy: string; createdAt: string }
export interface MappingEntry { matchType: string; sourceCode: string; treatment: string | null; blockedCategory: string | null; drcCategory: string | null }
export interface AgentLink { agencyName: string; tan: string; agentName: string; taan: string; validFrom: string }
export interface Signatory { id: string; name: string; email: string; position: string | null; validFrom: string; validTo: string | null }

export interface PortfolioRow {
  entityId: string; legalName: string; trn: string; entityKind: string; filingFrequency: string; periodId: string | null;
  periodStart: string | null; periodEnd: string | null; dueDate: string | null; state: string | null; daysLeft: number | null;
  blockingOpen: number | null; warningsOpen: number | null; netVat: string | null; versionNo: number | null;
}
export interface DeadlineRow {
  entityId: string; legalName: string; periodId: string; periodEnd: string; dueDate: string; state: string; daysLeft: number;
  blockingOpen: number; netVat: string | null; urgency: string;
}

export interface BoxDefinition { code: string; section: string; label: Record<string, string>; columns: string[]; computed: boolean; entryGuide: Record<string, string> }
export interface ReturnVersion {
  id: string; versionNo: number; status: string; formVersion: string; rulePackVersion: string; boxValues: string; memberValues: string;
  valuesHash: string; diff: string | null; lineCount: number; trigger: string; createdBy: string; createdAt: string;
}
export interface ReturnView { period: Period; version: ReturnVersion | null; form: BoxDefinition[]; ruleDraft: boolean }
export type BoxValues = Record<string, { amount: string; vat: string; adjustment: string }>;

export interface TaxLine {
  id: string; logicalLineId: string; revision: number; emirate: string | null; periodId: string; documentId: string | null; lineNo: number;
  lineKind: string; adjBox: string | null; sourceSystem: string; direction: string; goodsOrServices: string | null; glAccount: string | null;
  taxCode: string | null; description: string | null; counterpartyTrn: string | null; counterpartyCountry: string | null; taxPoint: string;
  currency: string; netAed: string; vatAed: string; recoverableVatAed: string; recoveryPct: string | null; treatment: string;
  blockedCategory: string | null; drcCategory: string | null; placeOfSupply: string | null; memo: boolean; reviewFlags: string[];
  classifiedBy: string; confidence: string | null; ruleIds: string[]; rulePackVersion: string; changeReason: string | null; changedBy: string;
}
export interface DrillRow { line: TaxLine; docNumber: string | null; docDate: string | null; docType: string | null; counterpartyName: string | null; einvoiceUuid: string | null; corner5Status: string | null; sourceRowNo: number | null; sourceFile: string | null }
export interface DrillPage { box: string; rows: DrillRow[]; page: number; size: number; total: number }
export interface LinesPage { rows: TaxLine[]; total: number; page: number; size: number }
export interface Lineage { line: TaxLine; document: Record<string, unknown>; sourceRow: Record<string, unknown>; batch: Record<string, unknown> }

export interface Batch {
  id: string; kind: string; channel: string; fileName: string; status: string; contentHash: string; rowsTotal: number; rowsLoaded: number;
  rowsUnchanged: number; rowsRejected: number; rowsHeld: number; createdBy: string; createdAt: string;
}
export interface LoadResult { batch: Batch; alreadyProcessed: boolean }
export interface RowView { id: string; rowNo: number; status: string; errors: string[]; raw: string }

export interface ReconException {
  id: string; ruleId: string; severity: string; status: string; message: string; details: string; amountAed: string | null; ownerId: string | null;
  dueDate: string | null; resolutionCode: string | null; resolutionNote: string | null; waiverReason: string | null; lockedAt: string | null;
  carriedFromId: string | null; firstOpenedAt: string;
}
export interface MatchProposal {
  id: string; docNumber: string; docDate: string; docNet: string; docVat: string; invoiceNumber: string; issueDate: string; invoiceNet: string; invoiceVat: string; score: string;
}
export interface ReconSummary { findings: number; blockingOpen: number; warningsOpen: number }
export interface TimingItem { id: string; account_type: string; amount_aed: number; description: string; created_by: string }

export interface Signoff { id: string; step: string; userName: string; note: string | null; signedAt: string; invalidatedAt: string | null; invalidatedReason: string | null }
export interface WorkflowStatus {
  period: Period; signoffs: Signoff[]; requiredSteps: string[]; partnerRequired: boolean; blockingExceptions: number; warnings: number; blockers: string[]; canApprove: boolean;
}
export interface Comment { id: string; objectType: string; objectId: string; authorName: string; body: string; createdAt: string }

export interface Evidence { id: string; fileName: string; sha256: string; sizeBytes: number; retentionClass: string; retainUntil: string; legalHold: boolean; description: string | null; uploadedBy: string; uploadedAt: string }
export interface Adjustment {
  id: string; type: string; boxCode: string; netAed: string; vatAed: string; description: string; reason: string | null; status: string; createdBy: string; decidedBy: string | null; createdAt: string;
}
export interface ReversalResult { reversed: number; reinstated: number; totalVatChange: string }
export interface CorrectionPreview { taxDifference: string; threshold: string; route: string; originalPayable: string; correctedPayable: string; changes: Record<string, string>[] }
export interface CorrectionCase { id: string; taxDifferenceAed: string; thresholdAed: string; route: string; targetPeriodId: string | null; reason: string; createdBy: string; createdAt: string }
export interface Pack { id: string; draftRules: boolean; packSha256: string; createdBy: string; createdAt: string }
export interface Payment { id: string; amountPaidAed: string; gibanReference: string; paidOn: string; matchStatus: string; matchDetail: string | null }
export interface FilingPosition {
  payable: string; refundable: boolean; paidAndMatched: string; outstanding: string;
  filing: { submissionRef: string; submittedOn: string; recordedBy: string } | null; payments: Payment[];
}
export interface RulePack { id: string; version: string; effectiveFrom: string; status: string; contentHash: string; loadedBy: string; signedOffBy: string | null; signedOffAt: string | null; signOffNote: string | null }
export interface AuditResult { intact: boolean; eventsChecked: number; firstBrokenSeq: number | null; problem: string | null; headHash: string | null }
export interface AuditEvent { seq: number; occurred_at: string; actor_type: string; actor_id: string; action: string; object_type: string; object_id: string; reason: string | null; hash: string }
export interface AppUser { id: string; displayName: string; email: string }
export interface Notification { id: string; title: string; body: string; read_at: string | null; created_at: string; period_id: string | null }
