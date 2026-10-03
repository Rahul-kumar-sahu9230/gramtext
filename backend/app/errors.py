class ServiceError(Exception):
    """An error that is safe to show to the user (never contains secrets)."""

    def __init__(self, message: str, status_code: int = 502):
        super().__init__(message)
        self.message = message
        self.status_code = status_code
