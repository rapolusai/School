/**
 * AI assistance: draft report-card remarks, an early warning watch list of students at risk, draft question papers
 * from the question bank and a small writing helper. Every draft comes from an {@link com.akshara.assist.AssistProvider};
 * in this phase the only provider is the simulated one, built from templates and the school's own data, so nothing
 * leaves the school. Requests are built from the minimum fields and never carry Aadhaar, phone numbers, addresses or
 * health data. Other modules are used only through their public types.
 */
package com.akshara.assist;
