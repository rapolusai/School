import { describe, expect, it } from "vitest";
import { ApiError, NETWORK_ERROR_STATUS } from "./api";
import { errorMessage, fieldErrorMessage, fieldErrorMessages, mapFieldMessage } from "./error-message";
import { translate, type Lang, type Translate } from "./i18n";

const tr = (lang: Lang): Translate => (key, vars) => translate(lang, key, vars);
const en = tr("en");
const hi = tr("hi");
const te = tr("te");

/** True when the text has an English word in it (a Latin word of 3+ letters; "MB" and placeholders aside). */
const hasEnglish = (text: string) => /[A-Za-z]{3,}/.test(text);

const problem = (init: ConstructorParameters<typeof ApiError>[0]) => new ApiError(init);

describe("errorMessage", () => {
  it("shows the server's own sentence in English", () => {
    const error = problem({ status: 409, title: "Conflict", detail: "Copy LIB00001 is already lent." });
    expect(errorMessage(error, en)).toBe("Copy LIB00001 is already lent.");
    expect(errorMessage(problem({ status: 404, title: "Not found" }), en)).toBe("Not found");
    expect(errorMessage(problem({ status: 500, title: "Boom" }), en)).toBe("Something went wrong. Try again.");
  });

  it("maps the shared handlers' sentences in Hindi and Telugu", () => {
    const fields = problem({ status: 400, title: "Check the form", detail: "Some fields need attention." });
    expect(errorMessage(fields, hi)).toBe(translate("hi", "validation.fixErrors"));
    const raced = problem({
      status: 409,
      title: "Changed by someone else",
      detail: "Someone else changed this at the same time. Reload and try again.",
    });
    expect(errorMessage(raced, te)).toBe(translate("te", "common.error.changed"));
    const exists = problem({ status: 409, title: "Already exists", detail: "This conflicts with something that already exists." });
    expect(errorMessage(exists, hi)).toBe(translate("hi", "common.error.exists"));
    const unreadable = problem({ status: 400, title: "Bad request", detail: "The request could not be read." });
    expect(errorMessage(unreadable, te)).toBe(translate("te", "common.error.badRequest"));
    const session = problem({ status: 401, title: "Sign in required", detail: "Your session has ended or is missing. Sign in again." });
    expect(errorMessage(session, hi)).toBe(translate("hi", "common.error.session"));
  });

  it("says not found in the user's language for any '<thing> was not found.'", () => {
    for (const thing of ["Exam", "Meeting", "RTE intake", "Title"]) {
      const error = problem({ status: 404, title: "Not found", detail: `${thing} was not found.` });
      expect(errorMessage(error, hi)).toBe(translate("hi", "common.error.notFound"));
      expect(errorMessage(error, te)).toBe(translate("te", "common.error.notFound"));
    }
  });

  it("falls back to a translated sentence for the status, never the English detail", () => {
    const cases: [number, string, string][] = [
      [400, "Check the form", "There are no working days in these dates; pick other dates."],
      [409, "Conflict", "Aman Tiwari already has 2 books, the most allowed."],
      [409, "Lottery done", "The intake cannot change after its lottery has run."],
      [404, "Gone", "No copy has the accession number NOPE99999."],
      [422, "Unprocessable", "Something module-specific."],
      [502, "Bad gateway", "Upstream said no."],
    ];
    for (const [status, title, detail] of cases) {
      for (const t of [hi, te]) {
        const message = errorMessage(problem({ status, title, detail }), t);
        expect(hasEnglish(message), `${status} ${detail}: ${message}`).toBe(false);
      }
    }
    expect(errorMessage(problem({ status: 409, title: "Conflict", detail: "x" }), hi)).toBe(
      translate("hi", "common.error.conflict"),
    );
  });

  it("names the hostel bed race in words", () => {
    const race = problem({
      status: 409,
      title: "Bed taken",
      detail: "Bed A in room 101 was just taken by another boarder. Pick another bed.",
    });
    expect(errorMessage(race, en)).toBe("Bed A in room 101 was just taken by another boarder. Pick another bed.");
    expect(errorMessage(race, hi)).toBe(translate("hi", "common.error.bedTaken"));
    expect(errorMessage(race, te)).toBe(translate("te", "common.error.bedTaken"));
  });

  it("says what the field's problem is when the page shows no field", () => {
    const tooLong = problem({
      status: 400,
      title: "Check the form",
      detail: "Some fields need attention.",
      errors: { text: "size must be between 0 and 500" },
    });
    expect(errorMessage(tooLong, en)).toBe("Some fields need attention: Use at most 500 characters.");
    expect(errorMessage(tooLong, hi)).toBe("कुछ फ़ील्ड जाँचें: अधिकतम 500 अक्षर रखें।");
    expect(hasEnglish(errorMessage(tooLong, te))).toBe(false);
  });

  it("keeps the network, permission and rate-limit messages", () => {
    expect(errorMessage(problem({ status: NETWORK_ERROR_STATUS, title: "Network error" }), te)).toBe(
      translate("te", "common.error.network"),
    );
    expect(errorMessage(problem({ status: 403, title: "Not allowed", detail: "nope" }), hi)).toBe(
      translate("hi", "common.error.forbidden"),
    );
  });
});

describe("field messages", () => {
  it("maps bean-validation defaults to dictionary keys", () => {
    expect(mapFieldMessage("must not be blank")?.key).toBe("validation.required");
    expect(mapFieldMessage("must not be null")?.key).toBe("validation.required");
    expect(mapFieldMessage("This is required.")?.key).toBe("validation.required");
    expect(mapFieldMessage("size must be between 0 and 40")).toEqual({ key: "validation.server.maxLength", vars: { max: "40" } });
    expect(mapFieldMessage("size must be between 3 and 40")).toEqual({
      key: "validation.server.lengthBetween",
      vars: { min: "3", max: "40" },
    });
    expect(mapFieldMessage("must be less than or equal to 2000")?.key).toBe("validation.server.atMost");
    expect(mapFieldMessage("must be greater than or equal to 1")?.key).toBe("validation.server.atLeast");
    expect(mapFieldMessage("must be greater than 0")?.key).toBe("validation.server.moreThan");
    expect(mapFieldMessage("must be a well-formed email address")?.key).toBe("validation.email");
    expect(mapFieldMessage("Enter a whole number.")?.key).toBe("validation.server.wholeNumber");
    expect(mapFieldMessage("This value is not valid.")?.key).toBe("validation.server.invalid");
    expect(mapFieldMessage("No copy has the accession number NOPE99999.")).toBeNull();
  });

  it("translates the login form's whitespace errors", () => {
    const errors = { schoolCode: "must not be blank", password: "size must be between 0 and 40" };
    expect(fieldErrorMessages(errors, hi)).toEqual({
      schoolCode: translate("hi", "validation.required"),
      password: "अधिकतम 40 अक्षर रखें।",
    });
    const telugu = fieldErrorMessages(errors, te);
    expect(Object.values(telugu).some(hasEnglish)).toBe(false);
  });

  it("keeps a module's own sentence in English and gives a translated one otherwise", () => {
    expect(fieldErrorMessage("No copy has the accession number NOPE99999.", en)).toBe(
      "No copy has the accession number NOPE99999.",
    );
    expect(fieldErrorMessage("No copy has the accession number NOPE99999.", hi)).toBe(
      translate("hi", "validation.server.invalid"),
    );
    expect(fieldErrorMessage("must not be blank", en)).toBe("Fill in this field.");
  });
});
