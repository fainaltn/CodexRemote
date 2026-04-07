import { readdir, realpath } from "node:fs/promises";
import { homedir } from "node:os";
import path from "node:path";
import type { FastifyInstance, FastifyRequest, FastifyReply } from "fastify";
import {
  BrowseProjectsParams,
  BrowseProjectsQuery,
  type BrowseProjectsResponse,
} from "@codexremote/shared";
import { LOCAL_HOST_ID } from "../constants.js";

function browseRoot(): string {
  return homedir();
}

async function normalizeBrowsePath(inputPath?: string): Promise<{
  rootPath: string;
  currentPath: string;
  parentPath: string | null;
}> {
  const rootPath = await realpath(browseRoot());
  const requested = inputPath ? path.resolve(inputPath) : rootPath;
  const currentPath = await realpath(requested).catch(() => rootPath);

  if (
    currentPath !== rootPath &&
    !currentPath.startsWith(`${rootPath}${path.sep}`)
  ) {
    return {
      rootPath,
      currentPath: rootPath,
      parentPath: null,
    };
  }

  const parent = path.dirname(currentPath);
  return {
    rootPath,
    currentPath,
    parentPath:
      currentPath === rootPath || parent === currentPath ? null : parent,
  };
}

export function projectRoutes() {
  return async function register(app: FastifyInstance): Promise<void> {
    app.get(
      "/api/hosts/:hostId/projects/browse",
      async (request: FastifyRequest, reply: FastifyReply) => {
        const params = BrowseProjectsParams.safeParse(request.params);
        if (!params.success) {
          return reply.status(400).send({ error: "Invalid route params" });
        }
        if (params.data.hostId !== LOCAL_HOST_ID) {
          return reply
            .status(404)
            .send({ error: `Host '${params.data.hostId}' not found` });
        }

        const query = BrowseProjectsQuery.safeParse(request.query);
        if (!query.success) {
          return reply.status(400).send({ error: "Invalid query params" });
        }

        const normalized = await normalizeBrowsePath(query.data.path);
        const dirEntries = await readdir(normalized.currentPath, {
          withFileTypes: true,
        }).catch(() => []);

        const entries = dirEntries
          .filter((entry) => entry.isDirectory())
          .map((entry) => ({
            name: entry.name,
            path: path.join(normalized.currentPath, entry.name),
          }))
          .sort((a, b) => a.name.localeCompare(b.name, "zh-CN"));

        const body: BrowseProjectsResponse = {
          rootPath: normalized.rootPath,
          currentPath: normalized.currentPath,
          parentPath: normalized.parentPath,
          entries,
        };
        return reply.send(body);
      },
    );
  };
}
