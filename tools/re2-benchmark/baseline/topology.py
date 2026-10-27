"""Frozen ordinary and 8-vCPU worker requirements; the language lifecycle suite runs on the larger one."""


def validate(platform):
    family = platform["platform"]
    expected = {"r8i": "intel", "r8g": "arm", "r9g": "arm"}
    if family not in expected or platform["architecture"] != expected[family]:
        raise ValueError("invalid platform architecture")
    if platform["instance_type"] != family + ".large" or platform["vcpus"] != "2":
        raise ValueError("ordinary workers require the pinned 2-vCPU R-family instance")
    if platform.get("concurrency_instance_type") != family + ".2xlarge" or platform.get("concurrency_vcpus") != "8":
        raise ValueError("concurrency workers require the pinned 8-vCPU R-family instance")
