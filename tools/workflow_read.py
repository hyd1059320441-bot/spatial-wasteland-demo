"""Read MCP response envelopes without discarding workflow result/status fields."""
import json
import sys
from pathlib import Path


def unwrap(value):
    while True:
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                try:
                    observation, _ = json.JSONDecoder().raw_decode(value.lstrip())
                except ValueError:
                    return value
                if isinstance(observation, dict) and observation.get("schemaVersion") == "spatialcraft.editor-workflow-response/v1":
                    value = observation
                else:
                    return value
        elif isinstance(value, dict) and "structuredContent" in value:
            value = value["structuredContent"]
        elif isinstance(value, dict) and "content" in value:
            value = "\n".join(c["text"] for c in value["content"] if c.get("type") == "text")
        elif isinstance(value, dict) and isinstance(value.get("result"), str):
            value = value["result"]
        else:
            return value


if __name__ == "__main__":
    response = unwrap(json.loads(Path(sys.argv[1]).read_text()))
    print(json.dumps(response, ensure_ascii=False, indent=2))
