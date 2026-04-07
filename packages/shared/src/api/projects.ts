import { z } from "zod";
import { MAX_ID_LENGTH, MAX_PATH_LENGTH } from "../limits.js";

export const BrowseProjectsParams = z.object({
  hostId: z.string().max(MAX_ID_LENGTH),
});
export type BrowseProjectsParams = z.infer<typeof BrowseProjectsParams>;

export const BrowseProjectsQuery = z.object({
  path: z.string().max(MAX_PATH_LENGTH).optional(),
});
export type BrowseProjectsQuery = z.infer<typeof BrowseProjectsQuery>;

export const ProjectDirectory = z.object({
  name: z.string(),
  path: z.string(),
});
export type ProjectDirectory = z.infer<typeof ProjectDirectory>;

export const BrowseProjectsResponse = z.object({
  rootPath: z.string(),
  currentPath: z.string(),
  parentPath: z.string().nullable(),
  entries: z.array(ProjectDirectory),
});
export type BrowseProjectsResponse = z.infer<typeof BrowseProjectsResponse>;
