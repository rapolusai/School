package com.akshara.lessons;

/**
 * The kinds of question in the bank. Every kind but SHORT_ANSWER is marked automatically; a short answer is marked
 * by the teacher.
 */
public enum QuestionType {
    /** Multiple choice with exactly one correct option. */
    MCQ_SINGLE,
    /** Multiple choice with one or more correct options; partly right answers get part of the marks. */
    MCQ_MULTI,
    TRUE_FALSE,
    SHORT_ANSWER,
    /** Fill in the blank: the answer is compared with the accepted answers, ignoring case and extra spaces. */
    FILL_BLANK;

    boolean hasOptions() {
        return this == MCQ_SINGLE || this == MCQ_MULTI;
    }

    boolean autoMarked() {
        return this != SHORT_ANSWER;
    }
}
