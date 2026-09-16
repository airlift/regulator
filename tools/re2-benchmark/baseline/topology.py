"""Frozen ordinary and multicore worker requirements."""


CONCURRENCY_SHARDS = frozenset({"lifecycle-shared-cold"})


def instance_type(platform, shard):
    if shard in CONCURRENCY_SHARDS:
        return platform.get("concurrency_instance_type", platform["instance_type"])
    return platform["instance_type"]


def validate(platform):
    family = platform["platform"]
    expected = {"r8i": "intel", "r8g": "arm", "r9g": "arm"}
    if family not in expected or platform["architecture"] != expected[family]:
        raise ValueError("invalid platform architecture")
    if platform["instance_type"] != family + ".large" or platform["vcpus"] != "2":
        raise ValueError("ordinary workers require the pinned 2-vCPU R-family instance")
    if platform.get("concurrency_instance_type") != family + ".2xlarge" or platform.get("concurrency_vcpus") != "8":
        raise ValueError("concurrency workers require the pinned 8-vCPU R-family instance")
