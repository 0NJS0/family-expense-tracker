import re
from typing import Annotated

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints

_USERNAME = re.compile(r"^[a-z0-9][a-z0-9._@-]{2,63}$")


def _normalise_username(value: str) -> str:
    value = value.strip().lower()
    if not _USERNAME.fullmatch(value):
        raise ValueError("username must be 3-64 characters: letters, digits, '.', '_', '@' or '-'")
    return value


Username = Annotated[str, AfterValidator(_normalise_username)]
# Upper bound protects the password hasher from oversized input.
Password = Annotated[str, Field(min_length=8, max_length=256)]
DisplayName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
ShortText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]


class ApiModel(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="forbid")


class Page[T](BaseModel):
    items: list[T]
    total: int
    limit: int
    offset: int
