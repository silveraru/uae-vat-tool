package ae.vattool.masterdata;

/**
 * Checks a Tax Registration Number. The spec asks for a checksum, but no published FTA algorithm
 * was found, so the default checks format only. When the FTA confirms an algorithm, register
 * another implementation of this interface.
 */
public interface TrnValidator {
    boolean isValid(String trn);
}
