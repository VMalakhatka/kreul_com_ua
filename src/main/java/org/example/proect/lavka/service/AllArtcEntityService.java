//package org.example.proect_lavka.service;
//
//import org.example.proect_lavka.entity.AllArtcEntity;
//import org.example.proect_lavka.repository.AllArtcEntityRepository;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.Optional;
//
//@Service
//public class AllArtcEntityService {
//    AllArtcEntityRepository repository;
//@Autowired
//    public AllArtcEntityService(AllArtcEntityRepository repository) {
//        this.repository = repository;
//        printAllArtic();
//    }
//
//    public void printAllArtic(){
//        Optional<List<AllArtcEntity>> listAllArtic = Optional.ofNullable( repository.findByCodArtic("KR-49602")).orElse(null);
//        System.out.println(listAllArtic);
//    }
//}
