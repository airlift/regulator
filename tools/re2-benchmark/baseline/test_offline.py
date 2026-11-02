import ipaddress
import socket
import threading
import unittest


def loopback(host):
    """Whether a host names this machine."""
    if host in (None, "localhost", b"localhost"):
        return True
    try:
        return ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host).is_loopback
    except ValueError:
        return False


def loopback_only(function, name):
    def guarded(*arguments, **options):
        if not loopback(name(*arguments)):
            raise AssertionError(f"a test reached for {name(*arguments)}, beyond this machine")
        return function(*arguments, **options)
    return guarded


# The SDK's clients in the tests are real, so a request that escapes its fake, as one from a thread that outlives its
# test can, would reach AWS. Every test module that loads the cloud imports this one before anything else, and from
# then on the process looks up and connects to nothing but this machine.
socket.getaddrinfo = loopback_only(socket.getaddrinfo, lambda host, *_: host)
for method in ("connect", "connect_ex"):
    setattr(socket.socket, method, loopback_only(getattr(socket.socket, method),
                                                 lambda sock, address: address[0] if sock.family in (
                                                     socket.AF_INET, socket.AF_INET6) else None))


class TestOffline(unittest.TestCase):
    def test_no_test_reaches_beyond_this_machine(self):
        # A thread that outlives its test's fakes is held to this machine too.
        refused = []

        def reach(host):
            try:
                socket.create_connection((host, 443), timeout=1)
            except AssertionError as error:
                refused.append(str(error))

        for host in ("192.0.2.1", "aws.example.invalid"):
            thread = threading.Thread(target=reach, args=(host,))
            thread.start()
            thread.join(10)
        self.assertEqual(["a test reached for 192.0.2.1, beyond this machine",
                          "a test reached for aws.example.invalid, beyond this machine"], refused)


if __name__ == "__main__":
    unittest.main()
