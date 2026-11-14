import uuid
from dataclasses import dataclass

from app.errors import Forbidden
from app.models import Household, Role, User

_RANK = {Role.VIEWER: 0, Role.MEMBER: 1, Role.ADMIN: 2}


@dataclass(frozen=True)
class HouseholdContext:
    """The authenticated user acting within one household they belong to."""

    user: User
    household: Household
    role: Role

    @property
    def user_id(self) -> uuid.UUID:
        return self.user.id

    @property
    def household_id(self) -> uuid.UUID:
        return self.household.id

    def require(self, minimum: Role) -> None:
        if _RANK[self.role] < _RANK[minimum]:
            raise Forbidden(f"This action requires the {minimum.value} role or higher")

    @property
    def is_admin(self) -> bool:
        return self.role is Role.ADMIN
