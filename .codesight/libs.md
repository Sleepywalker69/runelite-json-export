# Libraries

- `mcp-server\src\api.ts`
  - function apiGet: (path) => Promise<any>
  - function apiPost: (path) => Promise<any>
  - function isApiError: (err) => string
  - function stripColorTags: (text) => string
  - function normalize: (text) => string
  - function fuzzyMatch: (target, query) => boolean
- `mcp-server\src\tools\debug.ts` — function registerDebugTools: (server) => void
- `mcp-server\src\tools\definitions.ts` — function registerDefinitionTools: (server) => void
- `mcp-server\src\tools\devtools.ts` — function registerDevTools: (server) => void
- `mcp-server\src\tools\live-state.ts` — function registerLiveStateTools: (server) => void
- `mcp-server\src\tools\player-sync.ts` — function registerPlayerSyncTools: (server) => void
- `mcp-server\src\tools\widgets.ts` — function registerWidgetTools: (server) => void
- `mcp-server\src\tools\wiki.ts` — function registerWikiTools: (server) => void
