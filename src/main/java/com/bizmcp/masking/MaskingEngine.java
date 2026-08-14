package com.bizmcp.masking;

import com.bizmcp.governance.Role;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies {@link Masked} policy to a tool result, returning a masked copy.
 *
 * <p><b>Why this is not a Jackson module.</b> Spec section 7.2 planned to mask
 * during serialization via a custom {@code ObjectMapper} module. Spike 2
 * (see docs/adr/ADR-003) showed that is not possible in Spring AI 2.0.0: tool
 * results are serialized by {@code AbstractMcpToolMethodCallback} through a
 * {@code private static final JsonHelper}, which wraps its own Jackson 3
 * {@code JsonMapper} and exposes no registration hook. A module registered on
 * the Boot {@code ObjectMapper} would simply never be consulted — and, exactly
 * as the spec feared, it would fail silently.
 *
 * <p>So masking happens one step earlier instead: the governance advice masks
 * the returned object before the framework ever sees it. That makes masking
 * independent of which mapper serializes it, which is a stronger guarantee
 * than the original design, not a weaker one.
 *
 * <p>Results are records, so masking produces a new instance via the canonical
 * constructor rather than mutating in place.
 */
@Component
public class MaskingEngine {

    private static final Map<Class<?>, RecordComponent[]> COMPONENT_CACHE = new ConcurrentHashMap<>();

    /**
     * @param value the tool result, possibly containing masked fields
     * @param role  the caller's role, which decides per-field exemptions
     * @return a copy with masking applied; the input is never mutated
     */
    public Object mask(Object value, Role role) {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();

        if (type.isRecord()) {
            return maskRecord(value, role);
        }
        if (value instanceof Collection<?> collection) {
            return maskCollection(collection, role);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> masked = new LinkedHashMap<>();
            map.forEach((k, v) -> masked.put(k, mask(v, role)));
            return masked;
        }
        if (type.isArray() && !type.getComponentType().isPrimitive()) {
            Object[] source = (Object[]) value;
            Object[] masked = Arrays.copyOf(source, source.length);
            for (int i = 0; i < masked.length; i++) {
                masked[i] = mask(masked[i], role);
            }
            return masked;
        }
        // Scalars and framework types are returned untouched. Masking is driven
        // by declared @Masked policy, never by guessing at string content.
        return value;
    }

    private Object maskCollection(Collection<?> collection, Role role) {
        Collection<Object> masked = (collection instanceof Set)
                ? new LinkedHashSet<>()
                : new ArrayList<>(collection.size());
        for (Object element : collection) {
            masked.add(mask(element, role));
        }
        return masked;
    }

    private Object maskRecord(Object record, Role role) {
        Class<?> type = record.getClass();
        RecordComponent[] components = COMPONENT_CACHE.computeIfAbsent(type, Class::getRecordComponents);

        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        boolean changed = false;

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();

            Object raw;
            try {
                // A record component's accessor is public, but the record itself
                // may not be — a package-private or nested result type would
                // otherwise fail here with IllegalAccessException. Masking must
                // not depend on how visible the DTO happens to be declared.
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                raw = accessor.invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "cannot read record component " + type.getSimpleName() + "." + component.getName(), e);
            }

            Masked policy = component.getAnnotation(Masked.class);
            Object masked;
            if (policy != null && raw instanceof String text) {
                masked = appliesTo(policy, role) ? policy.strategy().applyNullSafe(text) : text;
            } else {
                masked = mask(raw, role);
            }
            if (masked != raw) {
                changed = true;
            }
            args[i] = masked;
        }

        if (!changed) {
            return record;
        }
        try {
            Constructor<?> canonical = type.getDeclaredConstructor(paramTypes);
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot rebuild masked record " + type.getName(), e);
        }
    }

    /** A field is masked unless the caller's role is explicitly exempted. */
    private boolean appliesTo(Masked policy, Role role) {
        for (Role exempt : policy.unmaskFor()) {
            if (exempt == role) {
                return false;
            }
        }
        return true;
    }

    /** Fields that must never appear unmasked, whatever the role. */
    public static List<String> alwaysMaskedFieldNames() {
        return List.of("customerName", "phone", "email");
    }
}
