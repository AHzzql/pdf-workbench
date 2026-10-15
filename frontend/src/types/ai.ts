import type { Rect } from "./pdf";
export interface AiDocument {
  id: string;
  name: string;
  pages: number;
  encrypted: boolean;
  status: string;
}
export interface Conversation {
  id: string;
  documentId: string;
  title: string;
  updatedAt: number;
}
export interface Citation {
  id: string;
  documentId: string;
  page: number;
  quote: string;
  bounds: Rect[];
}
export interface Chapter {
  id: string;
  title: string;
  page: number;
  endPage: number;
  source: string;
}
export type ReadingMode =
  "AUTO" | "WELCOME" | "ASK" | "BRIEF" | "DETAILED" | "CHAPTER";
export interface AiMessage {
  id: string;
  conversationId: string;
  role: string;
  text: string;
  status: string;
  error: string;
  mode: ReadingMode;
  question: string;
  citations: Citation[];
  sequence: number;
}
