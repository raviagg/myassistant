from mcp.server.fastmcp import FastMCP
from client import make_client
from tools import (
    persons, households, person_household, relationships,
    documents, facts, schemas, reference, audit, files,
)

mcp = FastMCP("myassistant")
http = make_client()

persons.register(mcp, http)
households.register(mcp, http)
person_household.register(mcp, http)
relationships.register(mcp, http)
documents.register(mcp, http)
facts.register(mcp, http)
schemas.register(mcp, http)
reference.register(mcp, http)
audit.register(mcp, http)
files.register(mcp, http)

if __name__ == "__main__":
    mcp.run()
