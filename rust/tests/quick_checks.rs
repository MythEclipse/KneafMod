#[test]
fn ndarray_dot_and_blake3() {
    use ndarray::array;
    use blake3::Hasher;

    let a = array![[1.0, 2.0], [3.0, 4.0]];
    let b = array![[5.0, 6.0], [7.0, 8.0]];
    let c = a.dot(&b);
    assert_eq!(c[[0,0]], 19.0);
    assert_eq!(c[[1,1]], 50.0);

    let mut hasher = Hasher::new();
    hasher.update(b"hello world");
    let out = hasher.finalize();
    assert!(out.as_bytes().len() == 32);
}
