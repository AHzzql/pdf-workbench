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
  fillColor: {
    rgb: string | null;
    colorSpace: string;
    components: number[];
  } | null;
}
export interface TextBlock extends Span {
  page: number;
  start: number;
  end: number;
}
export interface PageText {
  page: number;
  text: string;
  spans: Span[];
  blocks: TextBlock[];
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
  name: string | null;
  signer: string | null;
  algorithm: string | null;
  integrity: "VALID" | "INVALID" | "UNKNOWN";
  hasLaterRevision: boolean | null;
  certificateValidity: "VALID" | "EXPIRED" | "NOT_YET_VALID" | "UNKNOWN";
  trustStatus: "TRUSTED" | "UNTRUSTED" | "NOT_EVALUATED";
  revocationChecked: boolean;
  certificate: {
    subject: string;
    issuer: string;
    serialNumber: string;
    notBefore: string;
    notAfter: string;
  } | null;
  reason: string | null;
}
export interface Bookmark {
  title: string;
  page: number | null;
  externalUri: string | null;
  children: Bookmark[];
}
export interface DocumentInfo {
  pageCount: number;
  standardMetadata: Record<string, string>;
  customMetadata: Record<string, string>;
  xmp: string | null;
  bookmarks: Bookmark[];
  annotations: {
    page: number;
    type: string;
    contents: string | null;
    author: string | null;
    bounds: Rect | null;
  }[];
  diagnostics: string[];
}
export interface Selection {
  page: number;
  bounds: Rect[];
}
