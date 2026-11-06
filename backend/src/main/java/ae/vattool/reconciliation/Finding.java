package ae.vattool.reconciliation;

import java.math.BigDecimal;
import java.util.Map;

/**
 * One problem a check found. The fingerprint names the problem so it can be recognised next run;
 * the dataHash covers its figures, so a waiver stops applying if the figures move.
 */
public record Finding(String ruleId, String fingerprint, String message, Map<String, Object> details,
                      BigDecimal amountAed, String dataHash) {}
