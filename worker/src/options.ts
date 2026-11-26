import { parseContext } from "./context";
import { ApiError } from "./errors";
import type { PolishLevel, TranscriptionOptions } from "./types";

const ALLOWED_COMMANDS = new Set(["new_line", "new_paragraph"]);
const ALLOWED_POLISH = new Set<PolishLevel>(["off", "light", "strong"]);
const LOCALE_PATTERN = /^[a-z]{2}(-[A-Z]{2})?$/u;

export function parseOptions(value: string | File | null): TranscriptionOptions {
  if (typeof value !== "string" || value.length > 10_000) {
    throw invalidOptions();
  }

  let candidate: unknown;
  try {
    candidate = JSON.parse(value);
  } catch {
    throw invalidOptions();
  }
  if (!isRecord(candidate)) throw invalidOptions();

  const locale = candidate.locale;
  const polish = candidate.polish;
  const glossary = candidate.glossary;
  const commands = candidate.commands;
  if (
    typeof locale !== "string" ||
    (locale !== "auto" && !LOCALE_PATTERN.test(locale)) ||
    typeof polish !== "string" ||
    !ALLOWED_POLISH.has(polish as PolishLevel) ||
    typeof candidate.sentenceStart !== "boolean" ||
    !Array.isArray(commands) ||
    !commands.every((command) => typeof command === "string" && ALLOWED_COMMANDS.has(command)) ||
    !Array.isArray(glossary) ||
    glossary.length > 100 ||
    !glossary.every(
      (term) => typeof term === "string" && term.trim().length > 0 && term.trim().length <= 80,
    )
  ) {
    throw invalidOptions();
  }

  const cleanedGlossary = glossary.map((term) => term.trim());
  if (cleanedGlossary.join("\n").length > 4_000) throw invalidOptions();
  let context: TranscriptionOptions["context"];
  try { context = parseContext(candidate.context); } catch { throw invalidOptions(); }
  return {
    ...(context ? { context } : {}),
    locale,
    polish: polish as PolishLevel,
    sentenceStart: candidate.sentenceStart,
    commands: [...new Set(commands)] as TranscriptionOptions["commands"],
    glossary: cleanedGlossary,
  };
}

function invalidOptions(): ApiError {
  return new ApiError(400, "INVALID_REQUEST", false, "The transcription options are invalid.");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
