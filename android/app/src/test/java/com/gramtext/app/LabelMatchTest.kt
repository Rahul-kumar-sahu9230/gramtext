package com.gramtext.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelMatchTest {
    private val label = "दवा दिन में दो बार भोजन के बाद लें।\nParacetamol 500 mg\nबच्चों की पहुंच से दूर रखें।"

    @Test fun identical() = assertTrue(sameLabel(label, label))

    @Test fun linesInAnotherOrder() =
        assertTrue(sameLabel("Paracetamol 500 mg\nबच्चों की पहुंच से दूर रखें।\nदवा दिन में दो बार भोजन के बाद लें।", label))

    @Test fun edgeLineMissing() =
        assertTrue(sameLabel("दवा दिन में दो बार भोजन के बाद लें।\nParacetamol 500 mg", label))

    @Test fun extraCutOffFragment() = assertTrue(sameLabel("$label\nबच्चो", label))

    @Test fun smallOcrDifferences() =
        assertTrue(sameLabel("दवा दिन मे दो बार भोजन के बाद लें।\nParacetamo1 500 mg\nबच्चों की पहुँच से दूर रखें।", label))

    @Test fun differentLabel() =
        assertFalse(sameLabel("विद्यालय का समय\nसुबह 8:00 बजे से दोपहर 2:00 बजे तक", label))

    @Test fun differentLabelSharingADose() = assertFalse(sameLabel("Crocin Advance 500 mg tablets", label))

    @Test fun shortTextGrowsIntoLongerLabel() = assertFalse(sameLabel("नमस्ते, आपका स्वागत है।", "नमस्ते"))

    @Test fun nothingReadBefore() = assertFalse(sameLabel(label, ""))

    @Test fun englishCaseAndPunctuation() = assertTrue(sameLabel("KEEP OUT OF REACH OF CHILDREN", "Keep out of reach of children."))
}
