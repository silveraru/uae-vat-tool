import { BrowserRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { AuthCallback } from "./AuthCallback";
import { Layout } from "./Layout";
import { PortfolioPage } from "../pages/PortfolioPage";
import { DeadlinesPage } from "../pages/DeadlinesPage";
import { NewEntityPage } from "../pages/NewEntityPage";
import { EntityPage } from "../pages/EntityPage";
import { RulePacksPage } from "../pages/RulePacksPage";
import { AuditPage } from "../pages/AuditPage";
import { AnalyticsPage } from "../pages/AnalyticsPage";
import { AuditCasesPage } from "../pages/AuditCasesPage";
import { AuditCasePage } from "../pages/AuditCasePage";
import { PracticePage } from "../pages/PracticePage";
import { SuggestionsTab } from "../pages/period/SuggestionsTab";
import { SchemesTab } from "../pages/period/SchemesTab";
import { ExportsTab } from "../pages/period/ExportsTab";
import { RefundTab } from "../pages/period/RefundTab";
import { PeriodLayout } from "../pages/period/PeriodLayout";
import { ReturnTab } from "../pages/period/ReturnTab";
import { DataTab } from "../pages/period/DataTab";
import { ExceptionsTab } from "../pages/period/ExceptionsTab";
import { LinesTab } from "../pages/period/LinesTab";
import { AdjustmentsTab } from "../pages/period/AdjustmentsTab";
import { ReviewTab } from "../pages/period/ReviewTab";
import { FilingTab } from "../pages/period/FilingTab";
import { EvidenceTab } from "../pages/period/EvidenceTab";
import { CorrectionsTab } from "../pages/period/CorrectionsTab";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallback />} />
          <Route element={<Layout />}>
            <Route path="/" element={<PortfolioPage />} />
            <Route path="/deadlines" element={<DeadlinesPage />} />
            <Route path="/entities/new" element={<NewEntityPage />} />
            <Route path="/entities/:id" element={<EntityPage />} />
            <Route path="/rule-packs" element={<RulePacksPage />} />
            <Route path="/audit" element={<AuditPage />} />
            <Route path="/practice" element={<PracticePage />} />
            <Route path="/entities/:id/analytics" element={<AnalyticsPage />} />
            <Route path="/entities/:id/audit-cases" element={<AuditCasesPage />} />
            <Route path="/audit-cases/:id" element={<AuditCasePage />} />
            <Route path="/periods/:id" element={<PeriodLayout />}>
              <Route index element={<ReturnTab />} />
              <Route path="data" element={<DataTab />} />
              <Route path="exceptions" element={<ExceptionsTab />} />
              <Route path="lines" element={<LinesTab />} />
              <Route path="suggestions" element={<SuggestionsTab />} />
              <Route path="adjustments" element={<AdjustmentsTab />} />
              <Route path="schemes" element={<SchemesTab />} />
              <Route path="exports" element={<ExportsTab />} />
              <Route path="refund" element={<RefundTab />} />
              <Route path="review" element={<ReviewTab />} />
              <Route path="filing" element={<FilingTab />} />
              <Route path="evidence" element={<EvidenceTab />} />
              <Route path="corrections" element={<CorrectionsTab />} />
            </Route>
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
