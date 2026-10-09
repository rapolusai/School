-- Test only: the runtime role with a login, as the infrastructure creates it in real environments.
create role akshara_app login password 'app-test-password' nosuperuser nobypassrls;
