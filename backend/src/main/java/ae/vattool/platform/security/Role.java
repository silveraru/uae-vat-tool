package ae.vattool.platform.security;

import java.util.EnumSet;
import java.util.Set;

/** The six personas from spec section 2. Every grant is scoped to a legal entity. */
public enum Role {
    PREPARER(EnumSet.of(Permission.VIEW, Permission.EDIT_DATA, Permission.SUBMIT_FOR_REVIEW,
            Permission.COMMENT, Permission.UPLOAD_EVIDENCE)),
    APPROVER(EnumSet.of(Permission.VIEW, Permission.APPROVE_AND_LOCK, Permission.COMMENT,
            Permission.RECORD_FILING)),
    TAX_REVIEWER(EnumSet.of(Permission.VIEW, Permission.EDIT_ADJUSTMENTS, Permission.REVIEW_SIGNOFF,
            Permission.COMMENT, Permission.UPLOAD_EVIDENCE, Permission.RECORD_FILING)),
    ENGAGEMENT_PARTNER(EnumSet.of(Permission.VIEW, Permission.PARTNER_SIGNOFF, Permission.COMMENT,
            Permission.VIEW_PORTFOLIO)),
    FIRM_ADMIN(EnumSet.of(Permission.VIEW, Permission.ADMINISTER, Permission.MANAGE_RULES,
            Permission.VIEW_PORTFOLIO, Permission.VIEW_AUDIT_LOG)),
    AUDITOR(EnumSet.of(Permission.VIEW_LOCKED_ONLY));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean grants(Permission p) {
        return permissions.contains(p);
    }
}
