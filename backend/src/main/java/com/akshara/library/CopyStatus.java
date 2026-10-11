package com.akshara.library;

/**
 * Where a copy is. AVAILABLE on the shelf; ISSUED to a member; RESERVED on the hold shelf for the member first in the
 * title's queue; LOST, DAMAGED or WITHDRAWN cannot be lent until staff put them back.
 */
public enum CopyStatus {
    AVAILABLE, ISSUED, RESERVED, LOST, DAMAGED, WITHDRAWN
}
