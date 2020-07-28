/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.model.base.entity.validation;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.entity.id.RegexPatterns;

@Immutable
public final class AttributePatternValidator extends AbstractPatternValidator {

    public static AttributePatternValidator getInstance(final CharSequence id) {
        return new AttributePatternValidator(id, RegexPatterns.ATTRIBUTE_PATTERN);
    }

    AttributePatternValidator(final CharSequence id, final RegexPatterns.PatternWithMessage patternWithMessage) {
        super(id, patternWithMessage);
    }
}