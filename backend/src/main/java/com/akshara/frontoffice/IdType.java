package com.akshara.frontoffice;

/** The kind of identity document a visitor showed at the gate. Only the kind is kept, never the number. */
public enum IdType {
    NONE, AADHAAR, VOTER_ID, DRIVING_LICENCE, PAN, PASSPORT, COMPANY_ID, OTHER
}
