"""Run transaction/DAO tests against a disposable cluster, never backend/.env or DATABASE_URL.

Python standard library only. Requires installed PostgreSQL binaries, JDK 17 and Maven Wrapper.
Retains the stopped cluster under target/verification for inspection; target is Git-ignored.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
from urllib.parse import quote


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pg-bin", required=True, type=Path)
    parser.add_argument("--maven-repo", type=Path)
    parser.add_argument("--maven-user-home", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--clean", action="store_true", help="Clean WAR/classes; cluster stays outside target")
    parser.add_argument("--live-gemini", action="store_true", help="Run explicit eight-step QA with one real Gemini call")
    parser.add_argument("--suite", choices=("transaction", "dao", "runtime", "auth"), default="transaction")
    parser.add_argument("--tomcat-home", type=Path, help="Tomcat 9 distribution, required for auth HTTP tests")
    args = parser.parse_args()
    if args.live_gemini and args.suite != "auth":
        parser.error("--live-gemini requires --suite auth")
    if args.suite == "auth" and (not args.tomcat_home or not (args.tomcat_home / 'bin/bootstrap.jar').is_file()):
        parser.error("auth requires --tomcat-home pointing to a Tomcat 9 distribution")
    backend = Path(__file__).resolve().parents[3]
    live_settings = {}
    if args.live_gemini:
        from dotenv import dotenv_values
        private = dotenv_values(backend / '.env')
        key = os.environ.get('GEMINI_API_KEY') or os.environ.get('GOOGLE_API_KEY') or private.get('GEMINI_API_KEY') or private.get('GOOGLE_API_KEY')
        if not key:
            raise SystemExit('Gemini key unavailable; live QA NOT RUN. Configure it privately, never in chat.')
        live_settings['EZAM_TEST_GEMINI_KEY'] = key
        model = os.environ.get('GEMINI_MODEL') or private.get('GEMINI_MODEL')
        if model:
            live_settings['EZAM_TEST_GEMINI_MODEL'] = model
    evidence = backend / "target" / "verification"
    evidence.mkdir(parents=True, exist_ok=True)
    task, database_name, marker, profile = {
        "transaction": ("T1_2", "ezam_t12_test", "EZam isolated transaction test T1.2", "postgres-integration"),
        "dao": ("T1_3", "ezam_t13_test", "EZam isolated DAO test T1.3", "dao-integration"),
        "runtime": ("T1_4", "ezam_t14_test", "EZam isolated runtime test T1.4", "runtime-integration"),
        "auth": ("T2_1", "ezam_t21_test", "EZam isolated auth test T2.1", "auth-integration"),
    }[args.suite]
    cluster_root = backend.parent / '.tools' / 'isolated-postgres' if args.clean else evidence
    cluster_root.mkdir(parents=True, exist_ok=True)
    cluster = Path(tempfile.mkdtemp(prefix=task.lower() + "-postgres-", dir=cluster_root))
    data = cluster / "data"
    executable_suffix = ".exe" if os.name == "nt" else ""

    def pg(name):
        path = args.pg_bin.resolve() / (name + executable_suffix)
        if not path.is_file():
            raise RuntimeError("Missing PostgreSQL test binary: " + name)
        return str(path)

    def native(command, *, stdin=None, env=None):
        # pg_ctl spawns a persistent postmaster. PIPE stdout can be inherited by that child
        # on Windows, keeping communicate() alive after pg_ctl exits. Use a real log file.
        with (cluster / "native.log").open("a", encoding="utf-8") as log:
            result = subprocess.run(command, input=stdin, text=True, encoding="utf-8", errors="replace",
                                    stdin=subprocess.DEVNULL if stdin is None else None,
                                    stdout=log, stderr=subprocess.STDOUT, env=env, timeout=40,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if result.returncode:
            raise RuntimeError("Isolated PostgreSQL setup/cleanup failed; inspect native.log locally.")

    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    password = secrets.token_urlsafe(32)
    password_file = cluster / "init-password.tmp"
    password_file.write_text(password + "\n", encoding="utf-8")
    environment = os.environ.copy()
    environment.update(PGHOST="127.0.0.1", PGHOSTADDR="127.0.0.1", PGPORT=str(port), PGUSER="ezam_test", PGPASSWORD=password,
                       PGDATABASE=database_name, PGCONNECT_TIMEOUT="5", PGAPPNAME="ezam-test-setup")
    environment.pop("DATABASE_URL", None)
    environment.pop("PGSERVICE", None)
    environment.pop("PGSERVICEFILE", None)
    environment.update(live_settings)
    report = {"isolation": "new local PostgreSQL cluster", "applicationDatabaseAccessed": False,
              "serverStarted": False, "mavenExitCode": None, "serverStopped": False}
    started = False
    exit_code = 1
    try:
        native([pg("initdb"), "-D", str(data), "--username=ezam_test", "--pwfile=" + str(password_file),
                "--auth-local=trust", "--auth-host=scram-sha-256", "--encoding=UTF8", "--no-locale"])
        password_file.unlink()
        # Bind only loopback on a free port; this does not touch the installed PostgreSQL service.
        native([pg("pg_ctl"), "-D", str(data), "-l", str(cluster / "postgres.log"),
                "-o", f"-h 127.0.0.1 -p {port} -c log_statement=none", "-w", "-t", "20", "start"])
        started = True
        report["serverStarted"] = True
        native([pg("createdb"), "--no-password", "--maintenance-db=postgres", database_name], env=environment)
        schema = (backend / "src/test/resources/dao/schema.sql").read_text(encoding="utf-8") if args.suite in ("dao", "runtime", "auth") else """
CREATE TABLE t12_parent (id varchar PRIMARY KEY, title varchar NOT NULL);
CREATE TABLE t12_child (id varchar PRIMARY KEY, parent_id varchar NOT NULL REFERENCES t12_parent(id), content varchar NOT NULL);
"""
        if args.suite == "runtime":
            # Test-only deferred constraint, to make PostgreSQL COMMIT itself fail after file prepare.
            schema += "\nCREATE TABLE runtime_commit_guard(id integer PRIMARY KEY, ref integer REFERENCES runtime_commit_guard(id) DEFERRABLE INITIALLY DEFERRED);\n"
        if args.suite == "auth":
            # Test-only deferred constraint trigger: INSERT succeeds, actual COMMIT fails.
            schema += """
CREATE FUNCTION auth_test_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.email = 'commit-failure@example.com')
       OR (TG_OP = 'UPDATE' AND NEW.ho_ten = 'commit-profile-failure')
       OR (TG_OP = 'UPDATE' AND NEW.email = 'password-failure@example.com' AND NEW.mat_khau <> OLD.mat_khau) THEN
        RAISE EXCEPTION 'synthetic deferred failure' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER auth_test_commit_guard AFTER INSERT OR UPDATE ON giao_vien
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth_test_fail_commit();
CREATE FUNCTION exam_test_fail_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ten_de_thi = 'fail-commit' THEN
        RAISE EXCEPTION 'synthetic deferred exam failure' USING ERRCODE = '08006';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER exam_test_commit_guard AFTER INSERT OR UPDATE ON de_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION exam_test_fail_commit();
CREATE FUNCTION exam_test_fail_question() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.noi_dung = 'fail-second-question' THEN NEW.dap_an_a := NULL; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exam_test_question_guard BEFORE INSERT ON cau_hoi FOR EACH ROW EXECUTE FUNCTION exam_test_fail_question();
CREATE FUNCTION exam_test_fail_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.ten_de_thi = 'fail-delete' THEN RAISE EXCEPTION 'synthetic delete failure'; END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER exam_test_delete_guard AFTER DELETE ON de_thi FOR EACH ROW EXECUTE FUNCTION exam_test_fail_delete();
CREATE SEQUENCE room_collision_attempt;
CREATE FUNCTION room_test_collision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ten_lop = 'force-collision-once' AND nextval('room_collision_attempt') = 1 THEN
        NEW.ma_code_phong_thi := 'COLLID';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER room_test_collision_guard BEFORE INSERT ON phong_thi FOR EACH ROW EXECUTE FUNCTION room_test_collision();
CREATE FUNCTION room_test_result_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hoc_sinh WHERE id_hs=NEW.id_hs AND ho_va_ten='fail-result') THEN
        RAISE EXCEPTION 'synthetic admission failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER room_test_result_guard AFTER INSERT ON ket_qua_thi FOR EACH ROW EXECUTE FUNCTION room_test_result_failure();
CREATE FUNCTION room_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM hoc_sinh WHERE id_hs=NEW.id_hs AND ho_va_ten='fail-admission-commit') THEN
        RAISE EXCEPTION 'synthetic admission commit failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER room_test_commit_guard AFTER INSERT ON ket_qua_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION room_test_commit_failure();
CREATE FUNCTION room_test_start_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ten_lop='fail-start-commit' AND NEW.trang_thai_phong='DANG_THI' THEN
        RAISE EXCEPTION 'synthetic start commit failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER room_test_start_guard AFTER UPDATE ON phong_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION room_test_start_failure();
CREATE FUNCTION recovery_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.trang_thai='MAT_KET_NOI' AND EXISTS (SELECT 1 FROM hoc_sinh WHERE id_hs=NEW.id_hs AND ho_va_ten='fail-disconnect-commit') THEN
        RAISE EXCEPTION 'synthetic recovery commit failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER recovery_test_commit_guard AFTER UPDATE ON ket_qua_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION recovery_test_commit_failure();
CREATE FUNCTION grading_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.trang_thai='HOAN_THANH' AND EXISTS (SELECT 1 FROM hoc_sinh WHERE id_hs=NEW.id_hs AND ho_va_ten='fail-grading-commit') THEN
        RAISE EXCEPTION 'synthetic grading commit failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER grading_test_commit_guard AFTER UPDATE ON ket_qua_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION grading_test_commit_failure();
CREATE FUNCTION close_test_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.trang_thai_phong='DA_DONG' AND NEW.ten_lop='fail-close-commit' THEN
        RAISE EXCEPTION 'synthetic close commit failure';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER close_test_commit_guard AFTER UPDATE ON phong_thi
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION close_test_commit_failure();
"""
        # Both identifiers/marker come only from fixed choices above, never from a connection URL.
        native([pg("psql"), "--no-password", "--no-psqlrc", "--set=ON_ERROR_STOP=1"], env=environment,
               stdin=f"COMMENT ON DATABASE {database_name} IS '{marker}';\n" + schema)
        environment["TEST_DATABASE_URL"] = (
            f"postgresql://ezam_test:{quote(password, safe='')}@127.0.0.1:{port}/{database_name}"
            f"?ApplicationName=ezam-{task.lower().replace('_', '')}-pool&connectTimeout=5&socketTimeout=15")
        if args.suite == "auth":
            environment["EZAM_TEST_TOMCAT_HOME"] = str(args.tomcat_home.resolve())
            environment["EZAM_TEST_PYTHON"] = sys.executable
        if args.maven_user_home:
            environment["MAVEN_USER_HOME"] = str(args.maven_user_home.resolve())
        maven = [str(backend / ("mvnw.cmd" if os.name == "nt" else "mvnw"))]
        if os.name != "nt":
            maven.insert(0, "sh")
        if args.maven_repo:
            maven.append("-Dmaven.repo.local=" + str(args.maven_repo.resolve()))
        if args.offline:
            maven.append("--offline")
        maven += ["--batch-mode", "--no-transfer-progress"]
        if args.clean:
            # Resolve the exact Maven clean target before any recursive filesystem operation.
            assert (backend / 'target').resolve() == backend.parent.resolve() / 'backend' / 'target'
            maven.append('clean')
        maven += ["verify", "-P" + profile + (",qa-integration" if args.live_gemini else "")]
        print("Running Maven verify with an isolated PostgreSQL test cluster.", flush=True)
        result = subprocess.run(maven, cwd=backend, env=environment, timeout=600)
        exit_code = result.returncode
        report["mavenExitCode"] = exit_code
    finally:
        password_file.unlink(missing_ok=True)
        # A failed startup can still leave a postmaster behind; stop our exact new data directory only.
        if started or (data / "postmaster.pid").exists():
            native([pg("pg_ctl"), "-D", str(data), "-m", "fast", "-w", "-t", "20", "stop"])
        with socket.socket() as check:
            check.settimeout(2)
            report["serverStopped"] = check.connect_ex(("127.0.0.1", port)) != 0
        evidence.mkdir(parents=True, exist_ok=True)
        report['cleanVerify'] = args.clean
        report['liveGeminiRequested'] = args.live_gemini
        (evidence / (task + "_runner.json")).write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("Isolated PostgreSQL shutdown verified: " + str(report["serverStopped"]), flush=True)
    return exit_code if report["serverStopped"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
