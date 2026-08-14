package com.bizmcp.masking;

import com.bizmcp.governance.Role;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as personal data that must be masked before it leaves the
 * server (spec section 7.2).
 *
 * <p>Declared on record components so the DTO itself carries the policy: the
 * masking pass is generic and needs no per-tool code.
 */
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Masked {

    MaskStrategy strategy();

    /**
     * Roles that may see the raw value. Everyone else gets the masked form.
     * Empty (the default) means the field is masked for every role.
     */
    Role[] unmaskFor() default {};
}
