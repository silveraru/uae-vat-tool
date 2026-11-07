export interface Grant { entityId: string | null; role: string }
export interface Me { id: string; displayName: string; tenantName: string; email: string; side: string; grants: Grant[] }

export interface Entity {
  id: string; legalName: string; legalNameAr: string | null; trn: string | null; entityKind: string; groupRepId: string | null;
  filingFrequency: string | null; stagger: string | null; firstPeriodStart: string | null; einvoiceGoLive: string | null;
  defaultRecoveryPct: string; partnerThresholdAed: string | null; requireReviewer: boolean; ftaGiban: string | null; requireEsignature: boolean; taxYearStartMonth: number | null;
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
  esignatureRequired: boolean; signatures: Signature[];
}
export interface Comment { id: string; objectType: string; objectId: string; authorName: string; body: string; createdAt: string }

export interface Evidence { id: string; fileName: string; sha256: string; sizeBytes: number; retentionClass: string; retainUntil: string; legalHold: boolean; description: string | null; uploadedBy: string; uploadedAt: string }
export interface Adjustment {
  id: string; type: string; boxCode: string; netAed: string; vatAed: string; description: string; reason: string | null; status: string; createdBy: string; decidedBy: string | null; createdAt: string;
}
export interface ReversalResult { reversed: number; reinstated: number; totalVatChange: string }
export interface CorrectionPreview { taxDifference: string; threshold: string; route: string; originalPayable: string; correctedPayable: string; changes: Record<string, string>[] }
export interface CorrectionCase { id: string; taxDifferenceAed: string; thresholdAed: string; route: string; targetPeriodId: string | null; reason: string; createdBy: string; createdAt: string }
export interface Pack { id: string; draftRules: boolean; packSha256: string; pdfArKey: string | null; xlsxArKey: string | null; createdBy: string; createdAt: string }
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

export interface GeneratedDoc {
  id: string; entityId: string; periodId: string | null; kind: string; objectId: string; fileName: string; contentType: string; sha256: string;
  sizeBytes: number; draftRules: boolean; createdBy: string; createdAt: string;
}
export interface PenaltyItem { code: string; base: string | null; ratePct: string | null; months: number; amount: string; capped: boolean }
export interface PenaltyEstimate { asOf: string; scheduleFound: boolean; source: string | null; items: PenaltyItem[]; total: string }
export interface DisclosureBoxRow { box: string; label: string; amountFrom: string; amountTo: string; vatFrom: string; vatTo: string; adjustmentFrom: string; adjustmentTo: string }
export interface Disclosure {
  id: string; correctionId: string; discoveredOn: string; dueBy: string; natureOfError: string; status: string; createdBy: string; approvedBy: string | null; approvedAt: string | null;
  submissionRef: string | null; submittedOn: string | null; settledOn: string | null; settledAmountAed: string | null;
}
export interface DisclosureView { disclosure: Disclosure; overdue: boolean; form: string; boxes: DisclosureBoxRow[]; taxDifferenceAed: string; penalty: PenaltyEstimate; documents: GeneratedDoc[] }
export interface RefundRequest {
  id: string; periodId: string; amountAed: string; timeLimitOn: string; transitional: boolean; status: string; createdBy: string; submissionRef: string | null; submittedOn: string | null;
  decidedOn: string | null; approvedAmountAed: string | null; decisionNote: string | null; receivedOn: string | null; receivedAmountAed: string | null;
}
export interface RefundPosition {
  refundable: boolean; filed: boolean; excessAed: string; timeLimitOn: string; withinLimit: boolean; transitional: boolean; form: string; request: RefundRequest | null; documents: GeneratedDoc[];
}
export interface FafResult { document: GeneratedDoc; purchaseRows: number; supplyRows: number; ledgerRows: number; purchaseVat: string; supplyVat: string; warnings: string[] }
export interface AuditCase {
  id: string; entityId: string; ftaReference: string; notifiedOn: string; periodFrom: string; periodTo: string; description: string | null; status: string; outcome: string | null;
  openedBy: string; openedAt: string; closedAt: string | null;
}
export interface AuditRequest { id: string; caseId: string; description: string; requestedOn: string; dueOn: string; status: string; responseNote: string | null; respondedOn: string | null }
export interface AuditCaseView { auditCase: AuditCase; requests: AuditRequest[]; requestEvidence: Evidence[]; documents: GeneratedDoc[]; heldPeriods: number }
export interface Einvoice { invoiceUuid: string; number: string; issueDate: string; netAed: string | null; vatAed: string | null; lifecycleStatus: string; corner5Status: string; source: string }
export interface Counterparty { id: string; trn: string; name: string; country: string; designatedZone: boolean; trnStatus: string; activeFrom: string | null; activeTo: string | null; checkedOn: string | null }
export interface EntityGrant { id: string; userId: string; entityId: string | null; role: string; validFrom: string; validTo: string | null }
export interface BankStatement {
  id: string; format: string; fileName: string; account: string; statementRef: string | null; openingBalance: string | null; closingBalance: string | null; balanceChecked: boolean;
  balanceProblem: string | null; txnCount: number; txnNew: number; loadedAt: string;
}
export interface BankImport { result: { statements: BankStatement[]; alreadyProcessed: boolean; supplierInvoicesMatched: number }; returnsSettled: number }
export interface BankTxn { id: string; bookedOn: string; amount: string; currency: string; direction: string; reference: string | null; counterparty: string | null; narrative: string | null }
export interface BankCandidate { txn: BankTxn; ibanInText: boolean; amountIsDue: boolean }
export interface ApportionResult {
  kind: string; method: string; from: string; to: string; directRecoverableAed: string; directExemptAed: string; taxableSuppliesAed: string; totalSuppliesAed: string; ratioPct: string;
  residualInputAed: string; targetRecoverableAed: string; alreadyRecoveredAed: string; deltaAed: string; furtherAdjustment: boolean; posted: boolean;
}
export interface BadDebtCandidate { documentId: string; docNumber: string; docDate: string; counterpartyName: string | null; counterpartyTrn: string | null; grossAed: string; paidAed: string; vatAed: string; alreadyRelievedAed: string }
export interface BadDebtRelieved { documentId: string; docNumber: string; docDate: string; reliefAed: string; recoveredAed: string; outstandingAed: string }
export interface BadDebtClaim { id: string; documentId: string; kind: string; amountAed: string; vatAed: string; writtenOffOn: string | null; notifiedOn: string | null; docNumber: string; createdAt: string }
export interface CapitalAsset { id: string; reference: string; description: string; category: string; costAed: string; inputVatAed: string; initialRecoveryPct: string; firstUsedOn: string; adjustmentYears: number; disposedOn: string | null; opening: boolean }
export interface AssetInterval { id: string; intervalNo: number; intervalEnd: string; taxableUsePct: string; vatAdjustment: string }
export interface CapitalAssetView { asset: CapitalAsset; intervals: AssetInterval[]; nextIntervalNo: number | null; nextIntervalEnd: string | null }
export interface AssetDue { asset: CapitalAsset; intervalNo: number; intervalEnd: string }
export interface MarginRow { docNumber: string; docDate: string; category: string; description: string | null; purchasePriceAed: string; acquisitionCostsAed: string; sellingPriceAed: string; marginAed: string; vatAed: string }
export interface ExportRow {
  documentId: string; docNumber: string; docDate: string; counterpartyName: string | null; counterpartyCountry: string | null; netAed: string; exportedOn: string | null; exitReference: string | null;
  officialEvidenceId: string | null; commercialEvidenceId: string | null; extendedUntil: string | null; limitOn: string; status: string;
}
export interface Suggestion {
  id: string; lineId: string; currentTreatment: string; treatment: string; confidence: string; method: string; reason: string; engineVersion: string; status: string;
  docNumber: string | null; counterpartyTrn: string | null; description: string | null; netAed: string; vatAed: string;
}
export interface SuggestionRun { linesExamined: number; suggestionsAdded: number; noSuggestion: number }
export interface Assignment { id: string; entityId: string; userId: string; displayName: string; duty: string; hasAccess: boolean }
export interface WorkloadRow { userId: string; displayName: string; entities: number; openPeriods: number; dueSoon: number; overdue: number; openExceptions: number; blockingExceptions: number }
export interface PracticeOverview { people: WorkloadRow[]; unassigned: { entityId: string; legalName: string; nextDue: string | null }[] }
export interface WebhookEndpoint { id: string; name: string; url: string; events: string[]; active: boolean }
export interface WebhookDelivery { id: string; eventType: string; status: string; attempts: number; lastStatus: number | null; lastError: string | null; createdAt: string }
export interface TrendPoint {
  periodId: string; periodStart: string; periodEnd: string; state: string; locked: boolean; outputTax: string; recoverableTax: string; netVat: string; inputTaxIncurred: string; recoveryRatePct: string;
  totalSupplies: string; effectiveRatePct: string; openExceptions: number; resolvedExceptions: number; waivedExceptions: number;
}
export interface Anomaly { code: string; severity: string; box: string | null; value: string; baseline: string | null; changePct: string | null; counterparty: string | null }
export interface WatchPosition {
  watch: { id: string; name: string; notes: string | null }; rollingAed: string; expectedNext30DaysAed: string; mandatoryAed: string; voluntaryAed: string; pctOfMandatory: string; status: string; asOf: string;
  months: { monthStart: string; taxableSuppliesAed: string }[];
}
export interface ConnectorProfileView { system: string; label: string; status: string; transport: string; note: string; kinds: string[] }
export interface Connector { id: string; entityId: string; system: string; baseUrl: string; authKind: string; hasSecret: boolean; kinds: string[]; enabled: boolean }
export interface ConnectorRun { id: string; kind: string; fromDate: string; toDate: string; status: string; rowsFetched: number; error: string | null; finishedAt: string }
export interface RetentionRow { periodId: string; entityId: string; legalName: string; periodEnd: string; state: string; realEstateRecords: boolean; retainUntil: string; expired: boolean; activeHolds: number }
export interface BundledPack { version: string; installed: boolean }
export interface Signature { id: string; typedName: string; signerEmail: string; signatureHash: string; signedAt: string }
