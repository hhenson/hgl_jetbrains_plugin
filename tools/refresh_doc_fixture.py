"""Refresh the editor fixture from the pinned compiler's documentation example."""
import argparse
import json
from pathlib import Path
import subprocess
from shutil import copyfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("compiler", type=Path, help="compiler checkout with a debug build of hgl")
compiler = parser.parse_args().compiler.resolve()
fixtures = compiler / "crates/hgl_cli/tests/fixtures"
output = Path(__file__).resolve().parents[1] / "src/test/testData/documentation"
export = subprocess.check_output([
    compiler / "target/debug/hgl", "doc", fixtures / "doc", fixtures / "test_local_declarations.hgl",
    fixtures / "doc_examples.hgl", fixtures / "doc_extraction.hgl", output / "native_property.hgl",
    "--format", "json", "--private",
])
model = json.loads(export)
names = {"extraction_check::describe_value", "extraction_check::prose_example",
         "core::format", "core::write", "core::smallest_step", "demo.documentation_examples::twice", "examples.apidoc::Box", "examples.apidoc::Side",
         "examples.apidoc::identity", "examples.apidoc::shifted", "examples.apidoc::internal", "examples.properties::Meter",
         "demo.test_local_declarations::classify", "demo.test_local_declarations::local_candidate::classify",
         "demo.test_local_declarations::with_a_type::Closed",
         "demo.test_local_declarations::independent_types_and_operator::Closed"}
for module in model["modules"]:
    module["declarations"] = [d for d in module["declarations"] if d["canonicalName"] in names]
model["modules"] = [m for m in model["modules"] if m["declarations"]]
copyfile(fixtures / "doc_extraction.hgl", output / "doc_extraction.hgl")
(output / "model-v1.json").write_text(json.dumps(model, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
