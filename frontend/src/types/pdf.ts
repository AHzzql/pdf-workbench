export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface Span {
  text: string;
  font: string;
  fontSize: number;
  bounds: Rect;
  fillColor: { rgb: string } | null;
}
export interface PageText {
  page: number;
  text: string;
  spans: Span[];
  paragraphs: { text: string }[];
  diagnostics: string[];
}
export interface TextResult {
  pages: PageText[];
}
export interface TableData {
  page: number;
  bounds: Rect;
  rows: string[][];
}
export interface SearchMatch {
  page: number;
  text: string;
  bounds: Rect[];
  start: number;
  end: number;
}
export interface SignatureResult {
  name: string;
  signer: string;
  integrity: string;
  hasLaterRevision: boolean;
  certificateValidity: string;
  trustStatus: string;
  revocationChecked: boolean;
  reason: string | null;
}
export interface Bookmark {
  title: string;
  page: number | null;
  externalUri: string | null;
  children: Bookmark[];
}
export interface DocumentInfo {
  standardMetadata: Record<string, string>;
  customMetadata: Record<string, string>;
  xmp: string | null;
  bookmarks: Bookmark[];
  annotations: {
    page: number;
    type: string;
    contents: string;
    author: string;
    bounds: Rect;
  }[];
  diagnostics: string[];
}
export interface Selection {
  page: number;
  bounds: Rect[];
}
