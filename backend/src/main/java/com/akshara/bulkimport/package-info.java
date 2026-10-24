/**
 * The school import: an existing school brings all its students (one sheet per class and section) and staff (one
 * Staff sheet) in from a single Excel workbook. The workbook is read by a small defensive reader; students and staff
 * are checked and written through the public import services of the students and staff modules, classes and
 * sections through the academics module. Nothing is written unless the whole file is clean. Staff get sign-ins
 * without a password and parents get none; no password is ever generated, stored or shown. Other modules are used
 * only through their public types.
 */
package com.akshara.bulkimport;
